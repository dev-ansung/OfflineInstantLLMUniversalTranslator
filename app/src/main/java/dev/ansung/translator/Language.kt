package dev.ansung.translator

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

data class Language(
    val displayName: String,
    val localName: String,
    val languageCode: String,
    val country: String
) {
    fun toLocale(): Locale = Locale.forLanguageTag(languageCode)
}

object LanguageProvider {
    private var languages: List<Language>? = null

    fun getLanguages(context: Context): List<Language> {
        if (languages != null) return languages!!

        val list = mutableListOf<Language>()
        val parser = context.resources.getXml(R.xml.languages)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "language") {
                val displayName = parser.getAttributeValue(null, "displayName")
                val localName = parser.getAttributeValue(null, "localName")
                val languageCode = parser.getAttributeValue(null, "languageCode")
                val country = parser.getAttributeValue(null, "country")
                list.add(Language(displayName, localName, languageCode, country))
            }
            eventType = parser.next()
        }
        languages = list
        return list
    }
}
