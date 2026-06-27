use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, Instant};

const MAX_FAILURES: u32 = 5;
const WINDOW: Duration = Duration::from_secs(60);

struct Entry {
    failures: u32,
    window_start: Instant,
}

pub struct RateLimiter {
    state: Mutex<HashMap<IpAddr, Entry>>,
}

impl RateLimiter {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(HashMap::new()),
        }
    }

    /// Returns false if this IP has exceeded the failure threshold within the window.
    pub fn check(&self, ip: IpAddr) -> bool {
        let mut map = self.state.lock().unwrap_or_else(|e| e.into_inner());
        match map.get(&ip) {
            None => true,
            Some(e) if e.window_start.elapsed() >= WINDOW => {
                map.remove(&ip);
                true
            }
            Some(e) => e.failures < MAX_FAILURES,
        }
    }

    pub fn record_failure(&self, ip: IpAddr) {
        let mut map = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let entry = map.entry(ip).or_insert(Entry {
            failures: 0,
            window_start: Instant::now(),
        });
        if entry.window_start.elapsed() >= WINDOW {
            entry.failures = 0;
            entry.window_start = Instant::now();
        }
        entry.failures += 1;
    }

    pub fn record_success(&self, ip: IpAddr) {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&ip);
    }
}
