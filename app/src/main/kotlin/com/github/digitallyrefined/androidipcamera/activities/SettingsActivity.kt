package com.github.digitallyrefined.androidipcamera.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
import com.github.digitallyrefined.androidipcamera.helpers.ListenInterface
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import com.github.digitallyrefined.androidipcamera.helpers.TlsHelper
import java.io.File

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val PICK_CERTIFICATE_FILE = 1
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Set up certificate selection preference
            findPreference<Preference>("certificate_path")?.apply {
                setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-pkcs12", "application/pkcs12", "*/*"))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent.createChooser(intent, "Select TLS Certificate"),
                        PICK_CERTIFICATE_FILE
                    )
                    true
                }

                setOnPreferenceChangeListener { _, _ ->
                    restartStreamingServer()
                    true
                }
            }

            val secureStorage = SecureStorage(requireContext())

            // Configure authentication enable/disable checkbox
            findPreference<androidx.preference.CheckBoxPreference>("enable_auth")?.apply {
                val enabled = isChecked
                findPreference<EditTextPreference>("username")?.isVisible = enabled
                findPreference<EditTextPreference>("password")?.isVisible = enabled

                setOnPreferenceChangeListener { _, newValue ->
                    val isAuthEnabled = newValue as Boolean
                    findPreference<EditTextPreference>("username")?.isVisible = isAuthEnabled
                    findPreference<EditTextPreference>("password")?.isVisible = isAuthEnabled
                    restartStreamingServer()
                    true
                }
            }

            // Configure TLS checkbox and version
            val tlsCheckbox = findPreference<androidx.preference.CheckBoxPreference>("tls_enabled")
            fun updateTlsVisibility(enabled: Boolean) {
                findPreference<ListPreference>("tls_version")?.isVisible = enabled
                findPreference<Preference>("certificate_path")?.isVisible = enabled
                findPreference<EditTextPreference>("certificate_password")?.isVisible = enabled
                findPreference<Preference>("test_certificate")?.isVisible = enabled
            }
            updateTlsVisibility(tlsCheckbox?.isChecked ?: true)
            tlsCheckbox?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                updateTlsVisibility(enabled)
                restartStreamingServer()
                true
            }
            findPreference<ListPreference>("tls_version")?.setOnPreferenceChangeListener { _, _ ->
                restartStreamingServer()
                true
            }

            // Configure username
            findPreference<EditTextPreference>("username")?.apply {
                text = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "")

                setOnPreferenceChangeListener { _, newValue ->
                    val username = newValue.toString()
                    if (username.isNotEmpty() && !InputValidator.isValidUsername(username)) {
                        Toast.makeText(requireContext(),
                            "Username must be 1-50 characters, letters/numbers/hyphens/underscores only",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    secureStorage.putSecureString(SecureStorage.KEY_USERNAME, username)
                    restartStreamingServer()
                    true
                }
            }

            // Configure password
            findPreference<EditTextPreference>("password")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.text = null
                    editText.hint = "Enter new password"
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val password = newValue.toString()
                    if (password.isEmpty()) {
                        return@setOnPreferenceChangeListener false
                    }

                    if (!InputValidator.isValidPassword(password)) {
                        Toast.makeText(
                            requireContext(),
                            "Password must be 8-128 characters with uppercase, lowercase, and number",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    secureStorage.putSecureString(SecureStorage.KEY_PASSWORD, password)
                    restartStreamingServer()
                    false
                }
            }

            // Configure streaming port preference
            findPreference<EditTextPreference>("server_port")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val portStr = newValue.toString()
                    val port = portStr.toIntOrNull()
                    if (port == null || port !in 1..65535) {
                        Toast.makeText(
                            requireContext(),
                            "Port must be between 1 and 65535",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }
                    summary = "Port $port"
                    restartStreamingServer()
                    true
                }
            }

            // Interface
            findPreference<ListPreference>(ListenInterface.PREF)?.apply {
                val options = ListenInterface.options()
                entries = options.map { it.first }.toTypedArray()
                entryValues = options.map { it.second }.toTypedArray()
                value = ListenInterface.value(requireContext())
                summaryProvider = Preference.SummaryProvider<ListPreference> {
                    it.entry ?: "All interfaces"
                }
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue.toString() !in entryValues) {
                        return@setOnPreferenceChangeListener false
                    }
                    restartStreamingServer()
                    true
                }
            }

            // Stream Quality summary
            findPreference<ListPreference>("stream_quality")?.apply {
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }

            // Stream Bitrate summary
            findPreference<ListPreference>("stream_bitrate")?.apply {
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }

            // Focus Mode summary
            findPreference<ListPreference>("focus_mode")?.apply {
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }

            // Certificate password
            findPreference<EditTextPreference>("certificate_password")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.text = null
                    editText.hint = "Enter certificate password"
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val password = newValue.toString()
                    if (!InputValidator.isValidCertificatePassword(password)) {
                        Toast.makeText(
                            requireContext(),
                            "Certificate password too long (max 256 characters)",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    if (password.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            "Certificate password is required",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    secureStorage.putSecureString(SecureStorage.KEY_CERT_PASSWORD, password)
                    Toast.makeText(
                        requireContext(),
                        "Certificate password saved, use 'Test Certificate Setup' to validate",
                        Toast.LENGTH_SHORT
                    ).show()
                    restartStreamingServer()
                    false
                }
            }

            // Add test certificate functionality
            findPreference<Preference>("test_certificate")?.apply {
                setOnPreferenceClickListener {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val certificatePath = prefs.getString("certificate_path", null)
                    val certPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")

                    if (certPassword.isNullOrEmpty()) {
                        Toast.makeText(requireContext(),
                            "Certificate password not configured, set it above first",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceClickListener true
                    }

                    val isValid = if (certificatePath != null) {
                        val privateFile = File(requireContext().filesDir, "certificate.p12")
                        if (privateFile.exists()) {
                            InputValidator.validateCertificateFile(privateFile, certPassword)
                        } else {
                            val certUri = Uri.parse(certificatePath)
                            InputValidator.validateCertificateUsability(requireContext(), certUri, certPassword)
                        }
                    } else {
                        InputValidator.validateBuiltInCertificate(requireContext(), certPassword)
                    }

                    if (isValid) {
                        Toast.makeText(requireContext(),
                            "✅ Certificate configuration is valid",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(),
                            "❌ Certificate validation failed, check password and certificate file",
                            Toast.LENGTH_LONG).show()
                    }
                    true
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == PICK_CERTIFICATE_FILE && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri ->
                    val certificatePath = uri.toString()

                    if (!InputValidator.isValidCertificatePath(requireContext(), certificatePath)) {
                        Toast.makeText(requireContext(),
                            "Invalid certificate file, must be a valid .p12 or .pfx file under 10MB",
                            Toast.LENGTH_LONG).show()
                        return@let
                    }

                    val secureStorage = SecureStorage(requireContext())
                    val certPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")
                    val certificateUri = Uri.parse(certificatePath)

                    val privateFile = File(requireContext().filesDir, "certificate.p12")
                    try {
                        requireContext().contentResolver.openInputStream(certificateUri)?.use { input ->
                            privateFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Unable to open the selected file")
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Could not store the certificate: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@let
                    }

                    if (uri.scheme == "content") {
                        try {
                            requireContext().contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {}
                    }

                    preferenceManager.sharedPreferences?.edit()?.apply {
                        putString("certificate_path", certificatePath)
                        apply()
                    }
                    findPreference<Preference>("certificate_path")?.summary = certificatePath

                    val unlocked = !certPassword.isNullOrEmpty() &&
                        InputValidator.validateCertificateFile(privateFile, certPassword)
                    if (!unlocked) {
                        secureStorage.removeSecureString(SecureStorage.KEY_CERT_PASSWORD)
                        Toast.makeText(requireContext(),
                            "Certificate selected. Please enter its password below.",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(),
                            "Certificate selected and validated successfully",
                            Toast.LENGTH_SHORT).show()
                    }
                    restartStreamingServer()
                }
            }
        }

        private fun restartStreamingServer() {
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(requireContext(), StreamingService::class.java).apply {
                    action = "RESTART_STREAMING"
                }
                requireContext().startService(intent)
            }, 500)
        }
    }
}
