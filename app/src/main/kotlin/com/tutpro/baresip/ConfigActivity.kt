package com.tutpro.baresip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*

import java.io.File

class ConfigActivity : AppCompatActivity() {

    internal lateinit var configFile: File
    internal lateinit var autoStart: CheckBox
    internal lateinit var dnsServers: EditText
    internal lateinit var opusBitRate: EditText
    internal lateinit var iceLite: CheckBox

    private var oldAutoStart = ""
    private var oldDnsServers = ""
    private var oldOpusBitrate = ""
    private var oldIceMode = ""
    private var save = false
    private var config = ""

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        configFile = File(applicationContext.filesDir.absolutePath + "/config")
        config = Utils.getFileContents(configFile)
        if (config.length <= 100) {
            Utils.alertView(this, "Internal Error", "Failed to read config file")
            finish()
            return
        }

        autoStart = findViewById(R.id.AutoStart) as CheckBox
        val asCv = Utils.getNameValue(config, "auto_start")
        oldAutoStart = if (asCv.size == 0) "no" else asCv[0]
        autoStart.isChecked = oldAutoStart == "yes"

        dnsServers = findViewById(R.id.DnsServers) as EditText
        val dsCv = Utils.getNameValue(config, "dns_server")
        var dsTv = ""
        for (ds in dsCv) dsTv += ", $ds"
        oldDnsServers = dsTv.trimStart(',').trimStart(' ')
        dnsServers.setText(oldDnsServers)

        opusBitRate = findViewById(R.id.OpusBitRate) as EditText
        val obCv = Utils.getNameValue(config, "opus_bitrate")
        oldOpusBitrate = if (obCv.size == 0) "28000" else obCv[0]
        opusBitRate.setText(oldOpusBitrate)

        iceLite = findViewById(R.id.IceLite) as CheckBox
        val imCv = Utils.getNameValue(config, "ice_mode")
        oldIceMode = if (imCv.size == 0) "full" else imCv[0]
        iceLite.isChecked = oldIceMode == "lite"

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        val inflater = menuInflater
        inflater.inflate(R.menu.check_icon, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        val intent = Intent(this, MainActivity::class.java)

        if (item.itemId == R.id.checkIcon) {

            var autoStartString = "no"
            if (autoStart.isChecked) autoStartString = "yes"
            if (oldAutoStart != autoStartString) {
                config = Utils.removeLinesStartingWithName(config, "auto_start")
                config += "\nauto_start $autoStartString\n"
                save = true
            }

            val dnsServers = dnsServers.text.toString().trim()
            if (dnsServers != oldDnsServers) {
                if (!checkDnsServers(dnsServers)) {
                    Utils.alertView(this, "Notice", "Invalid DNS Servers: $dnsServers")
                    return false
                }
                config = Utils.removeLinesStartingWithName(config, "dns_server")
                for (server in dnsServers.split(","))
                    config += "\ndns_server ${server.trim()}\n"
                save = true
            }

            val opusBitRate = opusBitRate.text.toString().trim()
            if (opusBitRate != oldOpusBitrate) {
                if (!checkOpusBitRate(opusBitRate)) {
                    Utils.alertView(this, "Notice", "Invalid Opus Bit Rate: $opusBitRate")
                    return false
                }
                config = Utils.removeLinesStartingWithName(config, "opus_bitrate")
                config += "\nopus_bitrate $opusBitRate\n"
                save = true
            }

            var iceModeString = "full"
            if (iceLite.isChecked) iceModeString = "lite"
            if (oldIceMode != iceModeString) {
                config = Utils.removeLinesStartingWithName(config, "ice_mode")
                config += "\nice_mode $iceModeString\n"
                save = true
            }

            if (save) {
                var newConfig = ""
                for (line in config.split("\n")) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.startsWith("#") || (trimmedLine.length == 0)) continue
                    // Log.d("Baresip", "Config line $trimmedLine")
                    newConfig += trimmedLine.split("#")[0] + "\n"
                }
                Utils.putFileContents(configFile, newConfig)
                // Api.reload_config()
            }
            setResult(RESULT_OK, intent)
            finish()
            return true

        } else if (item.itemId == android.R.id.home) {

            Log.d("Baresip", "Back array was pressed at Config")
            setResult(Activity.RESULT_CANCELED, intent)
            finish()
            return true

        } else return super.onOptionsItemSelected(item)
    }

    fun onClick(v: View) {
        when (v) {
            findViewById(R.id.AutoStartTitle) as TextView-> {
                Utils.alertView(this, "Start Automatically", getString(R.string.autoStart))
            }
            findViewById(R.id.DnsServersTitle) as TextView -> {
                Utils.alertView(this, "DNS Servers", getString(R.string.dnsServers))
            }
            findViewById(R.id.OpusBitRateTitle) as TextView-> {
                Utils.alertView(this, "Opus Bit Rate", getString(R.string.opusBitRate))
            }
            findViewById(R.id.IceLiteTitle) as TextView-> {
                Utils.alertView(this, "ICE Lite Mode", getString(R.string.iceLite))
            }
        }
    }

    private fun checkDnsServers(dnsServers: String): Boolean {
        if (dnsServers.length == 0) return true
        for (server in dnsServers.split(","))
            if (!Utils.checkHostPort(server.trim(), true)) return false
        return true
    }

    private fun checkOpusBitRate(opusBitRate: String): Boolean {
        val number = opusBitRate.toIntOrNull()
        if (number == null) return false
        return (number >=6000) && (number <= 510000)
    }

}

