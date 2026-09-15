package com.example.stealthsos

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etWord = findViewById<EditText>(R.id.etSettingWord)
        val etHoldTime = findViewById<EditText>(R.id.etSettingHoldTime)
        val etContact = findViewById<EditText>(R.id.etSettingContact)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        val prefs = getSharedPreferences("StealthPrefs", Context.MODE_PRIVATE)
        etWord.setText(prefs.getString("SECRET_WORD", "help"))
        etHoldTime.setText(prefs.getInt("HOLD_TIME", 5).toString())
        etContact.setText(prefs.getString("EMERGENCY_CONTACT", "8667285995, 9087434571"))

        btnSave.setOnClickListener {
            val word = etWord.text.toString().trim().lowercase()
            val holdStr = etHoldTime.text.toString().trim()
            val contact = etContact.text.toString().trim()

            if (word.isNotEmpty() && holdStr.isNotEmpty() && contact.isNotEmpty()) {
                val holdTime = holdStr.toIntOrNull() ?: 5
                prefs.edit()
                    .putString("SECRET_WORD", word)
                    .putInt("HOLD_TIME", holdTime)
                    .putString("EMERGENCY_CONTACT", contact)
                    .apply()

                Toast.makeText(this, "Settings Saved Successfully!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            }
        }
    }
}