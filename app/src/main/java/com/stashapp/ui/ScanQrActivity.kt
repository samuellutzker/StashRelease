package com.stashapp.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.stashapp.R

class ScanQrActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_scan_qr)
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
