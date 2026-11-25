package com.example.kidshield.data

import android.content.Context
import android.content.SharedPreferences

class UserPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("kidshield_user_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_PRO = "key_is_pro_user"
        private const val KEY_PRO_TXN_REF = "key_pro_txn_ref"
    }

    var isProUser: Boolean
        get() = prefs.getBoolean(KEY_IS_PRO, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PRO, value).apply()

    var proTxnRef: String?
        get() = prefs.getString(KEY_PRO_TXN_REF, null)
        set(value) = prefs.edit().putString(KEY_PRO_TXN_REF, value).apply()
}
