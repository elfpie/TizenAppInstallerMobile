package com.tizenappinstallermobile.tizen

data class InstalledTvApp(val appId: String, val name: String, val version: String?, val installedAt: String?, val path: String?)
object TvInventory {
    fun parse(output: String): List<InstalledTvApp> {
        val apps = mutableListOf<InstalledTvApp>()
        val fields = linkedMapOf<String, String>()
        fun flush() {
            val id = fields["app_tizen_id"] ?: fields["application_id"] ?: fields["app_id"]
            if (!id.isNullOrBlank()) apps.add(InstalledTvApp(id, fields["app_title"] ?: fields["app_name"] ?: id,
                fields["app_version"]?.ifBlank { null }, fields["install_date"]?.ifBlank { null }, fields["app_installed_path"]))
            fields.clear()
        }
        output.replace("\u0000", "").lineSequence().forEach { original ->
            val line = original.trim()
            if (line.length >= 4 && line.all { it == '-' || it.isWhitespace() }) flush()
            else {
                val unpadded = line.replace(Regex("^-{3,}\\s*"), "").replace(Regex("\\s*-{3,}$"), "")
                val match = Regex("([a-zA-Z_]+)\\s*=\\s*(.*)").matchEntire(unpadded)
                if (match != null) {
                    val key = match.groupValues[1].lowercase()
                    if (fields.containsKey(key)) flush()
                    fields[key] = match.groupValues[2].trim()
                }
            }
        }
        flush()
        check(apps.isNotEmpty() || Regex("apps\\s+count\\s*:\\s*0\\b", RegexOption.IGNORE_CASE).containsMatchIn(output)) {
            "The TV did not return an installed-app listing. Check Developer Mode and try again."
        }
        return apps.distinctBy { it.appId }
    }
}
