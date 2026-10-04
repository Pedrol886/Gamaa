package com.gama.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

class GamaCalendarPermissionActivity : Activity() {
    private val requestCode = 4607

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (GamaCalendarManager.hasReadPermission(this) && GamaCalendarManager.hasWritePermission(this)) {
            finishPending()
            return
        }
        requestPermissions(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            requestCode,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != this.requestCode) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            finishPending()
        } else {
            Toast.makeText(this, "Sem a permissão de calendário o Gama não consegue criar compromissos.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun finishPending() {
        if (GamaCalendarManager.commitPending(this)) {
            Toast.makeText(this, "Compromisso adicionado à agenda.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
