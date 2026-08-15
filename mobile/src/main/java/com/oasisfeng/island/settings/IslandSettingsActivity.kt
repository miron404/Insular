@file:Suppress("DEPRECATION")

package com.oasisfeng.island.settings

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.app.ActionBar
import android.app.Activity.RESULT_OK
import android.app.AlertDialog
import android.app.Fragment
import android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE
import android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.Intent.ACTION_BOOT_COMPLETED
import android.content.Intent.ACTION_MY_PACKAGE_REPLACED
import android.content.Intent.ACTION_OPEN_DOCUMENT
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_PERMISSIONS
import android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
import android.graphics.Typeface.BOLD
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Build.VERSION_CODES.P
import android.os.Build.VERSION_CODES.Q
import android.os.Build.VERSION_CODES.S
import android.os.Bundle
import android.os.Process
import android.preference.EditTextPreference
import android.preference.Preference
import android.preference.TwoStatePreference
import android.provider.Settings
import android.text.Editable
import android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.style.StyleSpan
import android.util.ArraySet
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.oasisfeng.android.content.pm.enableComponent
import com.oasisfeng.android.content.pm.getComponentName
import com.oasisfeng.android.ui.Dialogs
import com.oasisfeng.android.ui.WebContent
import com.oasisfeng.island.Config
import com.oasisfeng.island.IslandNameManager
import com.oasisfeng.island.TempDebug
import com.oasisfeng.island.analytics.analytics
import com.oasisfeng.island.appops.AppOpsCompat
import com.oasisfeng.island.data.helper.isSystem
import com.oasisfeng.island.mobile.BuildConfig
import com.oasisfeng.island.mobile.R
import com.oasisfeng.island.notification.NotificationIds
import com.oasisfeng.island.setup.IslandSetup
import com.oasisfeng.island.util.*
import com.oasisfeng.island.util.DevicePolicies.PreferredActivity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Settings for each managed profile, also as launcher activity in managed profile.
 *
 * Created by Oasis on 2019-10-12.
 */
@ProfileUser class IslandSettingsFragment: android.preference.PreferenceFragment() {

    @Deprecated("Deprecated in Java") override fun onResume() {
        super.onResume()
        val activity = activity; val pm = activity.packageManager
        activity.title = IslandNameManager.getName(activity)

        val policies = DevicePolicies(activity.applicationContext)
        val isProfileOrDeviceOwner = policies.isProfileOrDeviceOwnerOnCallingUser

        if (SDK_INT !in P..Q) removeAppOpsRelated()     // Both Mainland and Island

        if (Users.isParentProfile() && ! isProfileOrDeviceOwner) {
            setup<Preference>(R.string.key_cross_profile) { remove(this) }
            setup<Preference>(R.string.key_cross_profile_widgets) { remove(this) }
            setup<Preference>(R.string.key_managed_mainland_setup) {
                summary = getString(R.string.pref_managed_mainland_summary) + getString(R.string.pref_managed_mainland_features)
                setOnPreferenceClickListener { true.also { WebContent.view(activity, Uri.parse(Config.URL_SETUP_MANAGED_MAINLAND.get())) }}}
            setup<Preference>(R.string.key_watcher) { isEnabled = false }
            setup<Preference>(R.string.key_island_watcher) { remove(this) }
            setup<Preference>(R.string.key_app_watcher) { remove(this) }
            setup<Preference>(R.string.key_setup) { remove(this) } }
        else setup<Preference>(R.string.key_managed_mainland_setup) { remove(this) }

        setup<Preference>(R.string.key_lock_capture_target) {
            if (! isProfileOrDeviceOwner) {
                isEnabled = false
                if (Users.isParentProfile()) summary = getString(R.string.pref_lock_capture_target_summary_mainland)
            } else setOnPreferenceClickListener { true.also {
                val captureIntent = PreferredActivity.Camera.getMatchingIntent()
                val candidates = pm.getInstalledApplications(0).filter { ! it.isSystem }.flatMap {  // Skip system apps to avoid hanging due to massive queries.
                    pm.queryIntentActivities(captureIntent.setPackage(it.packageName), 0)
                }.map { it.activityInfo }
                if (candidates.isEmpty())
                    return@also Unit.also { Dialogs.buildAlert(activity, 0, R.string.prompt_no_capture_app).show() }
                val current = captureIntent.setPackage(null).resolveActivity(pm)

                val reset = DialogInterface.OnClickListener { _, _ ->
                    captureIntent.resolveActivity(pm)?.packageName?.also { pkg ->
                        policies.clearPersistentPreferredActivity(pkg).also {
                            if (BuildConfig.DEBUG) Toast.makeText(activity, "Cleared: $pkg", Toast.LENGTH_LONG).show() }}}
                val list = candidates.map { it.loadLabel(pm).run {
                    if (it.getComponentName() != current) this
                    else SpannableStringBuilder(this).apply { setSpan(StyleSpan(BOLD), 0, length, SPAN_EXCLUSIVE_EXCLUSIVE) }}}
                Dialogs.buildList(activity, null, list.toTypedArray()) { _, which ->
                    reset.onClick(null, 0)   // Reset first
                    val target = candidates[which]
                    policies.setPersistentPreferredActivity(PreferredActivity.Camera, target.getComponentName())
                }.setNeutralButton(R.string.button_reset_to_default, reset).show() }}}

        if (SDK_INT in P..Q && isProfileOrDeviceOwner) { // App Ops in Android R is a mess (being reset now and then), do not support it on Android R at present.
            setupPreferenceForManagingAppOps(R.string.key_manage_read_phone_state, READ_PHONE_STATE, AppOpsCompat.OP_READ_PHONE_STATE,
                    R.string.pref_privacy_read_phone_state_title, SDK_INT <= P)
            setupPreferenceForManagingAppOps(R.string.key_manage_read_sms, READ_SMS, AppOpsCompat.OP_READ_SMS,
                    R.string.pref_privacy_read_sms_title)
            setupPreferenceForManagingAppOps(R.string.key_manage_location, ACCESS_COARSE_LOCATION, AppOpsCompat.OP_COARSE_LOCATION,
                    R.string.pref_privacy_location_title)
            if (Settings.Global.getInt(activity.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0)
                setupPreferenceForManagingAppOps(R.string.key_manage_storage, READ_EXTERNAL_STORAGE,
                        AppOpsCompat.OP_READ_EXTERNAL_STORAGE, R.string.pref_privacy_storage_title) }

        setup<Preference>(R.string.key_cross_profile) {
            if (SDK_INT <= Q || ! isProfileOrDeviceOwner) return@setup remove(this)
            onClick {
                val pkgs = pm.getInstalledPackages(GET_PERMISSIONS or MATCH_UNINSTALLED_PACKAGES)
                        .filter { it.requestedPermissions?.contains(INTERACT_ACROSS_PROFILES) == true
                                && (it.applicationInfo?.uid ?: 0) != Process.myUid() }  // Exclude extension pack
                val entries = pkgs.map { it.applicationInfo?.loadLabel(pm) ?: it.packageName }.toTypedArray()
                val allowedPackages: Set<String> = policies.invoke(DPM::getCrossProfilePackages)
                val allowed = BooleanArray(entries.size) { index -> pkgs[index].packageName in allowedPackages }
                Dialogs.buildCheckList(activity, activity.getText(R.string.prompt_manage_cross_profile_apps),
                        entries, allowed) { _, which, checked -> allowed[which] = checked }
                        .setNeutralButton(R.string.action_close) { _,_ ->
                            pkgs.mapIndexedNotNullTo(ArraySet()) { index, pkg -> if (allowed[index]) pkg.packageName else null }
                                    .toSet().also { policies.setCrossProfilePackages(it) }}
                        .setPositiveButton(R.string.prompt_manage_cross_profile_apps_footer, null)
                        .show().apply { getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false } }}

        setup<Preference>(R.string.key_cross_profile_widgets) {
            val widgetManager = activity.getSystemService<AppWidgetManager>()
            if (! isProfileOrDeviceOwner || widgetManager == null) return@setup remove(this)
            onClick {
                val pkgToAppInfo = widgetManager.installedProviders
                    .map { if (SDK_INT >= S) it.activityInfo else Hacks.AppWidgetProviderInfo_providerInfo.get(it) }
                    .associate { it.packageName to it.applicationInfo }
                val pkgs = pkgToAppInfo.keys.toList()
                val entries = pkgToAppInfo.values.map { it.loadLabel(pm) }.toTypedArray()
                val allowedPackages: Set<String> = policies.invoke(DPM::getCrossProfileWidgetProviders).toSet()
                val allowed = BooleanArray(entries.size) { index -> pkgs[index] in allowedPackages }
                Dialogs.buildCheckList(activity, activity.getText(R.string.pref_manage_cross_profile_widgets_summary), entries, allowed) { _, which, checked ->
                    if (checked) policies.invoke(DPM::addCrossProfileWidgetProvider, pkgs[which])
                    else policies.invoke(DPM::removeCrossProfileWidgetProvider, pkgs[which])
                }.setNeutralButton(R.string.action_close, null).show() }}

        setupNotificationChannelTwoStatePreference(this, R.string.key_island_watcher, SDK_INT >= P && ! Users.isParentProfile(), NotificationIds.IslandWatcher) {
            if (SDK_INT >= Q) summary = getString(R.string.pref_island_watcher_summary) +
                    "\n" + getString(R.string.pref_island_watcher_summary_appendix_api29) }
        setupNotificationChannelTwoStatePreference(this, R.string.key_app_watcher, SDK_INT >= O, NotificationIds.IslandAppWatcher)

        setup<Preference>(R.string.key_install_ca_cert) {
            if (! isProfileOrDeviceOwner) return@setup remove(this)
            onClick {   // No EXTRA_MIME_TYPES: certificate extensions are mapped inconsistently across providers, we validate the content instead.
                startActivityForResult(Intent(ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    REQUEST_CODE_INSTALL_CA_CERT) }}

        setup<Preference>(R.string.key_manage_ca_certs) {
            if (! isProfileOrDeviceOwner) return@setup remove(this)
            onClick { showManageCaCertsDialog(policies) }}

        setup<Preference>(R.string.key_reprovision) {
            if (Users.isParentProfile() && ! isProfileOrDeviceOwner) return@setup remove(this)
            setOnPreferenceClickListener { true.also { @SuppressLint("InlinedApi")
                val action = if (policies.isActiveDeviceOwner) ACTION_PROVISION_MANAGED_DEVICE else ACTION_PROVISION_MANAGED_PROFILE
                ContextCompat.startForegroundService(activity, Intent(action).setPackage(Modules.MODULE_ENGINE)) }}}
        setup<Preference>(R.string.key_destroy) {
            if (Users.isParentProfile()) {
                if (! isProfileOrDeviceOwner) return@setup remove(this)
                setTitle(R.string.pref_rescind_title)
                summary = getString(R.string.pref_rescind_summary) + getString(R.string.pref_managed_mainland_features) + "\n" }
            setOnPreferenceClickListener { true.also {
                if (Users.isParentProfile()) IslandSetup.requestDeviceOrProfileOwnerDeactivation(activity)
                else IslandSetup.requestProfileRemoval(activity) }}}
    }

    @Deprecated("Deprecated in Java") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE_INSTALL_CA_CERT) return super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) data?.data?.also { confirmCaCertInstall(it) }
    }

    /** Loads the certificate picked by the user and asks for explicit confirmation before trusting it. */
    private fun confirmCaCertInstall(uri: Uri) {
        val activity = activity ?: return
        val cert = try { loadCaCertificate(uri) } catch (e: CertLoadException) { return toast(e.prompt) }
        val der = try { cert.encoded } catch (e: CertificateEncodingException) {
            return reportCaCertFailure(R.string.prompt_ca_cert_install_failed, "Error encoding CA certificate", e) }

        val policies = DevicePolicies(activity.applicationContext)
        val installed = try { policies.invoke(DPM::hasCaCertInstalled, der) } catch (e: RuntimeException) {
            return reportCaCertFailure(R.string.prompt_ca_cert_install_failed, "Error querying installed CA certificates", e) }
        if (installed) return toast(R.string.prompt_ca_cert_already_installed)

        Dialogs.buildAlert(activity, getString(R.string.prompt_ca_cert_install_confirm_title),
                describe(cert, der) + "\n\n" + getString(R.string.prompt_ca_cert_install_confirm_warning))
            .withOkButton { installCaCert(policies, der) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun installCaCert(policies: DevicePolicies, der: ByteArray) {
        val installed = try { policies.invoke(DPM::installCaCert, der) } catch (e: RuntimeException) {
            return reportCaCertFailure(R.string.prompt_ca_cert_install_failed, "Error installing CA certificate", e) }
        toast(if (installed) R.string.prompt_ca_cert_installed else R.string.prompt_ca_cert_install_failed)
    }

    /** @throws CertLoadException if the content cannot be read, parsed as X.509, or is not a certificate authority. */
    private fun loadCaCertificate(uri: Uri): X509Certificate {
        val resolver = activity?.contentResolver ?: throw CertLoadException(R.string.prompt_ca_cert_install_failed)
        val stream = (try { resolver.openInputStream(uri) }
            catch (e: Exception) { throw CertLoadException(R.string.prompt_ca_cert_install_failed, e) })
            ?: throw CertLoadException(R.string.prompt_ca_cert_install_failed)
        val bytes = (try { stream.use { it.readAtMost(MAX_CERT_FILE_SIZE) }}
            catch (e: IOException) { throw CertLoadException(R.string.prompt_ca_cert_install_failed, e) })
            ?: throw CertLoadException(R.string.prompt_ca_cert_too_large)

        val cert = parseCertificate(bytes) ?: throw CertLoadException(R.string.prompt_ca_cert_invalid)
        // A certificate is only usable as a trust anchor with BasicConstraints CA:TRUE, which installCaCert() does not verify.
        if (cert.basicConstraints < 0) throw CertLoadException(R.string.prompt_ca_cert_not_ca)
        return cert
    }

    /** @return the first X.509 certificate in [bytes] (DER, PEM or bare BASE64), or null if none can be parsed. */
    private fun parseCertificate(bytes: ByteArray): X509Certificate? {
        val factory = CertificateFactory.getInstance("X.509")
        try { return factory.generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate }
        catch (e: CertificateException) { Log.d(TAG, "Not DER or PEM encoded, retrying as bare BASE64", e) }
        return try {    // BASE64 without PEM armor, or PEM preceded by extra text (e.g. "Bag Attributes").
            val base64 = String(bytes, Charsets.ISO_8859_1).substringAfter(PEM_HEADER).substringBefore(PEM_FOOTER)
            factory.generateCertificate(ByteArrayInputStream(Base64.decode(base64, Base64.DEFAULT))) as? X509Certificate }
        catch (e: Exception) { null.also { Log.w(TAG, "Failed to decode certificate", e) }}
    }

    private fun showManageCaCertsDialog(policies: DevicePolicies) {
        val activity = activity ?: return
        // Lists every user-added CA trusted in this profile, not just the ones installed by Island.
        val installed = try { policies.invoke(DPM::getInstalledCaCerts) } catch (e: RuntimeException) {
            return reportCaCertFailure(R.string.prompt_ca_cert_query_failed, "Error querying installed CA certificates", e) }
        if (installed.isEmpty()) {
            Dialogs.buildAlert(activity, null, getString(R.string.prompt_no_ca_certs_installed)).show()
            return }

        val certs = installed.map { der -> der to parseCertificate(der) }
        val labels: List<CharSequence> = certs.map { (_, cert) ->
            cert?.subjectX500Principal?.name ?: getString(R.string.ca_cert_unrecognized) }
        Dialogs.buildList(activity, getString(R.string.pref_manage_ca_certs_title), labels.toTypedArray()) { _, which ->
            val (der, cert) = certs[which]
            Dialogs.buildAlert(activity, getString(R.string.prompt_ca_cert_remove_confirm_title),
                    cert?.let { describe(it, der) } ?: getString(R.string.ca_cert_unrecognized))
                .withOkButton { uninstallCaCert(policies, der) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }.show()
    }

    private fun uninstallCaCert(policies: DevicePolicies, der: ByteArray) {
        try { policies.execute(DPM::uninstallCaCert, der) } catch (e: RuntimeException) {
            return reportCaCertFailure(R.string.prompt_ca_cert_remove_failed, "Error removing CA certificate", e) }
        toast(R.string.prompt_ca_cert_removed)
    }

    /** Human-readable summary shown before a certificate is trusted or removed. */
    private fun describe(cert: X509Certificate, der: ByteArray): String {
        val lines = mutableListOf(
            getString(R.string.ca_cert_detail_subject, cert.subjectX500Principal.name),
            getString(R.string.ca_cert_detail_issuer, cert.issuerX500Principal.name),
            getString(R.string.ca_cert_detail_expiry, DateFormat.getDateFormat(activity).format(cert.notAfter)),
            getString(R.string.ca_cert_detail_fingerprint, sha256Fingerprint(der)))
        if (cert.notAfter.before(Date())) lines += getString(R.string.prompt_ca_cert_expired)
        return lines.joinToString("\n")
    }

    private fun sha256Fingerprint(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der)
        .map { "%02X".format(it) }.chunked(16).joinToString("\n") { it.joinToString(":") }

    /** @return the whole content, or null if it exceeds [limit] bytes. */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val content = ByteArrayOutputStream(); val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) return content.toByteArray()
            if (content.size() + read > limit) return null
            content.write(chunk, 0, read) }
    }

    private fun reportCaCertFailure(@StringRes prompt: Int, message: String, e: Exception) {
        analytics().logAndReport(TAG, message, e)
        toast(prompt)
    }

    private fun toast(@StringRes prompt: Int) { activity?.also { Toast.makeText(it, prompt, Toast.LENGTH_LONG).show() }}

    private fun removeAppOpsRelated() {
        setup<Preference>(R.string.key_privacy_appops) { remove(this) }
        setup<Preference>(R.string.key_manage_read_phone_state) { remove(this) }
        setup<Preference>(R.string.key_manage_read_sms) { remove(this) }
        setup<Preference>(R.string.key_manage_location) { remove(this) }
        setup<Preference>(R.string.key_manage_storage) { remove(this) }
    }

    private fun setupPreferenceForManagingAppOps(key: Int, permission: String, op: Int, @StringRes prompt: Int, precondition: Boolean = true) {
        setup<Preference>(key) {
            if (SDK_INT >= P && precondition) {
                setOnPreferenceClickListener { true.also { OpsManager(activity, permission, op).startOpsManager(prompt) }}
            } else remove(this) }
    }

    private fun setupNotificationChannelTwoStatePreference(fragment: Fragment, @StringRes key: Int, visible: Boolean,
            notificationId: NotificationIds, block: (TwoStatePreference.() -> Unit)? = null) {
        setup<TwoStatePreference>(key) {
            if (visible && SDK_INT >= O) {
                isChecked = ! notificationId.isBlocked(context)
                setOnPreferenceChangeListener { _, _ -> true.also {
                    if (context.checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        fragment.requestPermissions(arrayOf(POST_NOTIFICATIONS), 0)
                    fragment.startActivity(notificationId.buildChannelSettingsIntent(context))
                }}
            } else remove(this)
            block?.invoke(this)
        }
    }

    private fun onIslandRenamed(name: String) {
        val activity = activity
        IslandNameManager.setName(activity, name)
        activity.title = name
    }

    private fun requestRenaming() {
        val activity = activity
        object: EditTextPreference(activity) {
            init {
                onAttachedToHierarchy(this@IslandSettingsFragment.preferenceManager)
                if (text.isNullOrEmpty()) text = IslandNameManager.getName(activity)
                editText.also { editText ->
                    editText.addTextChangedListener(object: TextWatcher {
                        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

                        override fun afterTextChanged(s: Editable) {
                            if (editText.text.any { it < ' ' }) editText.error = getString(R.string.prompt_invalid_input) }})

                setOnPreferenceChangeListener { _, name -> (editText.error == null).also { if (it)
                    onIslandRenamed(name.toString()) }}

                showDialog(null) }
            }
        }
    }

    @Deprecated("Deprecated in Java") override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        preferenceManager.setStorageDeviceProtected()
        addPreferencesFromResource(R.xml.pref_island)
    }

    @Deprecated("Deprecated in Java") override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (! Users.isParentProfile()) {
            inflater.inflate(R.menu.pref_island_actions, menu)
            if (BuildConfig.DEBUG) menu.findItem(R.id.menu_test).isVisible = true }
    }

    @Deprecated("Deprecated in Java") override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_rename -> true.also { requestRenaming() }
            R.id.menu_test -> true.also { TempDebug.run(activity) }
            android.R.id.home -> true.also { activity.finish() }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

/** Only enabled in profile managed by Island, as a sole indicator for owner user to identify. */
class IslandSettingsActivity: CallerAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callingPackage != packageName) actionBar?.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_HOME)
        else actionBar?.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP)
        fragmentManager.beginTransaction().replace(android.R.id.content, IslandSettingsFragment()).commit()
        IslandNameManager.syncNameToParentProfile(this)     // In case previous name synchronization failed
    }

    class Enabler: BroadcastReceiver() {    // One-time enabler for

        override fun onReceive(context: Context, intent: Intent) {      // ACTION_LOCKED_BOOT_COMPLETED is unnecessary for activity
            if (intent.action != ACTION_BOOT_COMPLETED && intent.action != ACTION_MY_PACKAGE_REPLACED) return
            if (Users.isParentProfile()) return     // Should never happen
            if (! DevicePolicies(context).isProfileOwner) return        // Profile managed by other app

            Log.i(TAG, "Enabling ${IslandSettingsActivity::class.java.simpleName}")
            context.enableComponent<IslandSettingsActivity>()
        }
    }
}

/** Failure to load a user-picked certificate, carrying the message to show. */
private class CertLoadException(@StringRes val prompt: Int, cause: Throwable? = null): Exception(cause)

private const val INTERACT_ACROSS_PROFILES = "android.permission.INTERACT_ACROSS_PROFILES"
private const val REQUEST_CODE_INSTALL_CA_CERT = 42
private const val MAX_CERT_FILE_SIZE = 512 * 1024   // Far beyond any X.509 certificate, just to bound the read.
private const val PEM_HEADER = "-----BEGIN CERTIFICATE-----"
private const val PEM_FOOTER = "-----END CERTIFICATE-----"
private const val TAG = "Island.ISA"
