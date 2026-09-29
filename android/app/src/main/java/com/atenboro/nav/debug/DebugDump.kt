package com.atenboro.nav.debug

import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

object DebugDump {

    fun collectTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val out = mutableListOf<String>()
        walk(root, out)
        return out
    }

    fun dumpTree(root: AccessibilityNodeInfo?, depth: Int = 0, sb: StringBuilder = StringBuilder()): String {
        if (root == null) return sb.toString()
        val indent = "  ".repeat(depth)
        val text = root.text?.toString()?.replace('\n', ' ') ?: ""
        val desc = root.contentDescription?.toString()?.replace('\n', ' ') ?: ""
        val cls = root.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = root.viewIdResourceName ?: ""
        sb.append(indent)
            .append(cls)
            .append(" id=").append(id)
            .append(" text=\"").append(text).append('"')
            .append(" desc=\"").append(desc).append('"')
            .append('\n')
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            dumpTree(child, depth + 1, sb)
            child.recycle()
        }
        return sb.toString()
    }

    fun writeToFile(dir: File, content: String): File {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "a11y-dump-${System.currentTimeMillis()}.txt")
        file.writeText(content)
        return file
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out)
            child.recycle()
        }
    }
}
