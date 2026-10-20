package dev.spendcodex.data.backup

import java.io.InputStream

/**
 * The texts that go into every archive, written once in the repository's docs/export and packed into the app as assets:
 * the format description, the JSON Schema, and the instructions and request for an AI in both languages.
 */
class DocAssets(private val open: (String) -> InputStream) {
    fun text(name: String): String = open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    val format: String get() = text("format.md")
    val schema: String get() = text("schema.json")
    fun aiReadme(english: Boolean): String = text(if (english) "ai-readme.en.md" else "ai-readme.ru.md")
    fun prompt(english: Boolean): String = text(if (english) "prompt.en.txt" else "prompt.ru.txt")
}
