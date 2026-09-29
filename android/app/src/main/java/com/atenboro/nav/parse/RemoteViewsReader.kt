package com.atenboro.nav.parse

import android.graphics.Bitmap
import android.widget.RemoteViews
import java.lang.reflect.Field

/**
 * Читает mActions у RemoteViews через reflection (setText / setImageBitmap и т.п.).
 * На новых Android имена полей могут отличаться — пробуем несколько вариантов.
 */
object RemoteViewsReader {

    data class Extract(
        val texts: List<String>,
        val bitmaps: List<Bitmap>
    )

    fun read(rv: RemoteViews?): Extract {
        if (rv == null) return Extract(emptyList(), emptyList())
        val texts = mutableListOf<String>()
        val bitmaps = mutableListOf<Bitmap>()
        try {
            val actions = findActions(rv) ?: return Extract(emptyList(), emptyList())
            for (action in actions) {
                if (action == null) continue
                digAction(action, texts, bitmaps)
            }
        } catch (_: Throwable) {
            // ignore — fallback на extras / apply()
        }
        return Extract(
            texts = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            bitmaps = bitmaps
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun findActions(rv: RemoteViews): List<Any?>? {
        val names = listOf("mActions", "actions")
        var cls: Class<*>? = rv.javaClass
        while (cls != null) {
            for (name in names) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    val v = f.get(rv) ?: continue
                    when (v) {
                        is List<*> -> return v as List<Any?>
                        is Array<*> -> return v.toList()
                    }
                } catch (_: NoSuchFieldException) {
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun digAction(action: Any, texts: MutableList<String>, bitmaps: MutableList<Bitmap>) {
        val methodName = readStringField(action, listOf("mMethodName", "methodName"))
        val value = readAnyField(action, listOf("mValue", "value", "bitmap", "mBitmap"))

        when {
            value is CharSequence -> {
                val s = value.toString()
                if (isUserFacingText(s)) texts += s
            }
            value is Bitmap -> bitmaps += value
            value is Array<*> -> value.forEach { item ->
                when (item) {
                    is CharSequence -> {
                        val s = item.toString()
                        if (isUserFacingText(s)) texts += s
                    }
                    is Bitmap -> bitmaps += item
                }
            }
        }

        // Nested RemoteViews (ViewGroupAction) — на новых API поле часто blocked
        try {
            val nested = readAnyField(action, listOf("mNestedViews", "nestedViews", "mRemoteViews"))
            if (nested is RemoteViews) {
                val inner = read(nested)
                texts += inner.texts
                bitmaps += inner.bitmaps
            }
        } catch (_: Throwable) {
        }

        if (methodName != null) {
            when {
                methodName.equals("setText", ignoreCase = true) && value is CharSequence -> {
                    val s = value.toString()
                    if (isUserFacingText(s)) texts += s
                }
                methodName.contains("Bitmap", ignoreCase = true) && value is Bitmap ->
                    bitmaps += value
                methodName.contains("Image", ignoreCase = true) && value is Bitmap ->
                    bitmaps += value
            }
        }

        // Только bitmap-поля — не тащим имена методов/viewId
        for (f in allFields(action.javaClass)) {
            val name = f.name
            if (!name.contains("itmap", ignoreCase = true) &&
                !name.contains("Bitmap", ignoreCase = true)
            ) {
                continue
            }
            try {
                f.isAccessible = true
                val v = f.get(action)
                if (v is Bitmap) bitmaps += v
            } catch (_: Throwable) {
            }
        }
    }

    private fun isUserFacingText(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 120) return false
        if (t.startsWith("android.")) return false
        if (t.startsWith("set") && t.length < 24 && !t.contains(' ')) return false
        if (t in listOf("setText", "setVisibility", "setImageBitmap", "setImageViewBitmap")) return false
        return true
    }

    private fun readStringField(obj: Any, names: List<String>): String? {
        val v = readAnyField(obj, names) ?: return null
        return v.toString()
    }

    private fun readAnyField(obj: Any, names: List<String>): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            for (name in names) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(obj)
                } catch (_: NoSuchFieldException) {
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun allFields(cls: Class<*>): List<Field> {
        val out = mutableListOf<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out += c.declaredFields
            c = c.superclass
        }
        return out
    }
}
