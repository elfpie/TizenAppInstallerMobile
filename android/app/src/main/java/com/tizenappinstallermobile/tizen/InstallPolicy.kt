package com.tizenappinstallermobile.tizen

object InstallPolicy {
    fun errorCode(output: String): String? = Regex("install failed\\[(\\d+),\\s*(-?\\d+)\\]", RegexOption.IGNORE_CASE)
        .find(output)?.let { it.groupValues[1] + ":" + it.groupValues[2] }

    private fun rejection(output: String): String {
        val code = errorCode(output)?.let { " ($it)" }.orEmpty()
        val reason = when {
            output.contains("certificate", true) || output.contains("signature", true) -> "TV rejected the signing certificate"
            else -> "TV rejected the installation"
        }
        return "$reason$code. See Settings → Activity for details."
    }
    fun isInstalled(list: String, info: PackageInfo): Boolean =
        list.split(Regex("[^A-Za-z0-9_.-]+" )).any { it == info.packageId || it == info.appId }

    fun verifyIdentity(previous: PackageInfo?, candidate: PackageInfo, previousAuthor: String?, author: String?) {
        if (previous != null) {
            require(previous.packageId == candidate.packageId && previous.appId == candidate.appId) {
                "This build has a different app identity. Choose the same variant to preserve settings and the signed-in account."
            }
        }
        if (previousAuthor != null) {
            require(previousAuthor == author) {
                "Author certificate changed. Import the original certificate before updating; your TV app was left in place."
            }
        }
    }

    fun verifyResult(output: String) {
        val text = output.lowercase()
        check(Regex("val\\[(-?\\d+)\\]").findAll(text).none { it.groupValues[1].toIntOrNull() != 0 }) {
            rejection(output)
        }
        check(!Regex("\\b(fail(?:ed|ure)?|error)\\b|install_failed|check certificate error").containsMatchIn(text)) {
            rejection(output)
        }
        check(text.contains("install completed") || text.contains("installing was successful") ||
            text.contains("success") || Regex("val\\[0\\]").containsMatchIn(text)) {
            "TV did not confirm installation success. See Settings → Activity for details."
        }
    }
}
