package com.dramabox

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaBoxPlugin : Plugin() {
    override fun load(context: Context) {
        val appContext = context.applicationContext
        val authPrefs = appContext.getSharedPreferences("dramabox_auth_v1", Context.MODE_PRIVATE)

        openSettings = { settingsContext ->
            val density = settingsContext.resources.displayMetrics.density
            val padding = (16 * density).toInt()
            val fields = linkedMapOf(
                "tn" to secretField(settingsContext, "tn — Bearer token", authPrefs.getString("tn", "")),
                "sn" to secretField(settingsContext, "sn — request signature", authPrefs.getString("sn", "")),
                "st" to secretField(settingsContext, "st — captured header", authPrefs.getString("st", "")),
                "device-id" to secretField(settingsContext, "device-id", authPrefs.getString("device-id", "")),
                "recSessionId" to secretField(
                    settingsContext,
                    "recSessionId — captured request body",
                    authPrefs.getString("recSessionId", "")
                )
            )

            val column = LinearLayout(settingsContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, padding / 2)
            }
            fields.values.forEach { field ->
                column.addView(
                    field,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                )
            }

            val scroll = ScrollView(settingsContext).apply { addView(column) }
            AlertDialog.Builder(settingsContext)
                .setTitle("إعدادات اختبار DramaBox")
                .setMessage(
                    "أدخل القيم كما ظهرت في طلب التطبيق الرسمي داخل Reqable. " +
                        "تُحفظ محليًا على هذا الهاتف فقط ولا تُرسل إلى المستودع. " +
                        "قد تكون التواقيع مرتبطة بالطلب الأصلي أو منتهية الصلاحية."
                )
                .setView(scroll)
                .setPositiveButton("حفظ") { _, _ ->
                    val editor = authPrefs.edit()
                    fields.forEach { (key, field) ->
                        val value = field.text?.toString()?.trim().orEmpty()
                        if (value.isBlank()) editor.remove(key) else editor.putString(key, value)
                    }
                    editor.apply()
                    Toast.makeText(
                        settingsContext,
                        "تم حفظ إعدادات اختبار DramaBox على الجهاز",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNeutralButton("مسح بيانات الجلسة") { _, _ ->
                    authPrefs.edit().clear().apply()
                    Toast.makeText(
                        settingsContext,
                        "تم مسح بيانات جلسة DramaBox المحلية",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        registerMainAPI(DramaBox(appContext))
    }

    private fun secretField(context: Context, label: String, value: String?): EditText {
        return EditText(context).apply {
            hint = label
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(value.orEmpty())
        }
    }
}
