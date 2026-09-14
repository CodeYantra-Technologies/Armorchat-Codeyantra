package com.codeyantra.armorchat.ui.navigation

object Routes {
    const val Splash = "splash"
    const val Welcome = "welcome"
    const val CreateIdentity = "identity/create"
    const val IdentityCreated = "identity/created"
    const val RestoreIdentity = "identity/restore"
    const val Home = "home"
    const val MyIdentity = "identity/mine"
    const val PrivacySecurity = "settings/privacy_security"
    const val NetworkPrivacy = "settings/network_privacy"
    const val EmergencyMesh = "settings/emergency_mesh"
    const val About = "about"
    const val OpenSourceLicenses = "about/open_source_licenses"
    const val Support = "support"
    const val TwoPhoneTest = "debug/two_phone"
    const val Chat = "chat/{conversationId}"
    const val ContactDetail = "contact/{contactId}"

    fun chat(conversationId: String) = "chat/$conversationId"
    fun contactDetail(contactId: String) = "contact/$contactId"
}
