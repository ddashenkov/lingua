/*
 * Copyright © 2018-2020 Peter M. Stahl pemistahl@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.pemistahl.lingua.api

import com.github.pemistahl.lingua.api.Language.AFRIKAANS
import com.github.pemistahl.lingua.api.Language.ALBANIAN
import com.github.pemistahl.lingua.api.Language.AZERBAIJANI
import com.github.pemistahl.lingua.api.Language.BASQUE
import com.github.pemistahl.lingua.api.Language.BELARUSIAN
import com.github.pemistahl.lingua.api.Language.BOKMAL
import com.github.pemistahl.lingua.api.Language.BOSNIAN
import com.github.pemistahl.lingua.api.Language.BULGARIAN
import com.github.pemistahl.lingua.api.Language.CATALAN
import com.github.pemistahl.lingua.api.Language.CHINESE
import com.github.pemistahl.lingua.api.Language.CROATIAN
import com.github.pemistahl.lingua.api.Language.CZECH
import com.github.pemistahl.lingua.api.Language.DANISH
import com.github.pemistahl.lingua.api.Language.ESTONIAN
import com.github.pemistahl.lingua.api.Language.FINNISH
import com.github.pemistahl.lingua.api.Language.FRENCH
import com.github.pemistahl.lingua.api.Language.GERMAN
import com.github.pemistahl.lingua.api.Language.HUNGARIAN
import com.github.pemistahl.lingua.api.Language.ICELANDIC
import com.github.pemistahl.lingua.api.Language.IRISH
import com.github.pemistahl.lingua.api.Language.ITALIAN
import com.github.pemistahl.lingua.api.Language.JAPANESE
import com.github.pemistahl.lingua.api.Language.KAZAKH
import com.github.pemistahl.lingua.api.Language.LATVIAN
import com.github.pemistahl.lingua.api.Language.LITHUANIAN
import com.github.pemistahl.lingua.api.Language.MACEDONIAN
import com.github.pemistahl.lingua.api.Language.MONGOLIAN
import com.github.pemistahl.lingua.api.Language.NORWEGIAN
import com.github.pemistahl.lingua.api.Language.NYNORSK
import com.github.pemistahl.lingua.api.Language.POLISH
import com.github.pemistahl.lingua.api.Language.PORTUGUESE
import com.github.pemistahl.lingua.api.Language.ROMANIAN
import com.github.pemistahl.lingua.api.Language.RUSSIAN
import com.github.pemistahl.lingua.api.Language.SERBIAN
import com.github.pemistahl.lingua.api.Language.SLOVAK
import com.github.pemistahl.lingua.api.Language.SLOVENE
import com.github.pemistahl.lingua.api.Language.SPANISH
import com.github.pemistahl.lingua.api.Language.SWEDISH
import com.github.pemistahl.lingua.api.Language.TURKISH
import com.github.pemistahl.lingua.api.Language.UKRAINIAN
import com.github.pemistahl.lingua.api.Language.UNKNOWN
import com.github.pemistahl.lingua.api.Language.VIETNAMESE
import com.github.pemistahl.lingua.internal.Alphabet
import com.github.pemistahl.lingua.internal.Ngram
import com.github.pemistahl.lingua.internal.TestDataLanguageModel
import com.github.pemistahl.lingua.internal.TrainingDataLanguageModel
import com.github.pemistahl.lingua.internal.util.extension.containsAnyOf
import java.util.SortedMap
import java.util.TreeMap
import java.util.regex.PatternSyntaxException
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Detects the language of given input text.
 */
class LanguageDetector internal constructor(
    internal val languages: MutableSet<Language>,
    internal val minimumRelativeDistance: Double,
    internal val numberOfLoadedLanguages: Int = languages.size
) {
    private val languagesWithUniqueCharacters = languages.filter { it.uniqueCharacters.isNotEmpty() }.asSequence()
    private val alphabetsSupportingExactlyOneLanguage = Alphabet.allSupportingExactlyOneLanguage().filterValues { it in languages }

    internal val unigramLanguageModels = loadLanguageModels(ngramLength = 1)
    internal val bigramLanguageModels = loadLanguageModels(ngramLength = 2)
    internal val trigramLanguageModels = loadLanguageModels(ngramLength = 3)
    internal val quadrigramLanguageModels = loadLanguageModels(ngramLength = 4)
    internal val fivegramLanguageModels = loadLanguageModels(ngramLength = 5)

    /**
     * Detects the language of given input text.
     *
     * @param text The input text to detect the language for.
     * @return The identified language or [Language.UNKNOWN].
     */
    fun detectLanguageOf(text: String): Language {
        val confidenceValues = computeLanguageConfidenceValues(text)

        if (confidenceValues.isEmpty()) return UNKNOWN
        if (confidenceValues.size == 1) return confidenceValues.firstKey()

        val mostLikelyLanguage = confidenceValues.firstKey()
        val mostLikelyLanguageProbability = confidenceValues.getValue(mostLikelyLanguage)

        val secondMostLikelyLanguage = confidenceValues.filterNot {
            it.key == mostLikelyLanguage
        }.maxBy { it.value }!!.key
        val secondMostLikelyLanguageProbability = confidenceValues.getValue(secondMostLikelyLanguage)

        if (mostLikelyLanguageProbability == secondMostLikelyLanguageProbability) return UNKNOWN
        if ((mostLikelyLanguageProbability - secondMostLikelyLanguageProbability) >= minimumRelativeDistance) {
            return mostLikelyLanguage
        }

        return UNKNOWN
    }

    fun computeLanguageConfidenceValues(text: String): SortedMap<Language, Double> {
        val values = TreeMap<Language, Double>()
        val cleanedUpText = cleanUpInputText(text)

        if (cleanedUpText.isEmpty() || NO_LETTER.matches(cleanedUpText)) return values

        val words = splitTextIntoWords(cleanedUpText)
        val languageDetectedByRules = detectLanguageWithRules(words)

        if (languageDetectedByRules != UNKNOWN) {
            values[languageDetectedByRules] = 1.0
            return values
        }

        val allProbabilities = mutableListOf<Map<Language, Double>>()
        val unigramCountsOfInputText = mutableMapOf<Language, Int>()
        var languagesSequence = filterLanguagesByRules(words)

        for (i in 1..5) {
            if (cleanedUpText.length < i) continue

            val testDataModel = TestDataLanguageModel.fromText(cleanedUpText, ngramLength = i)
            allProbabilities.add(computeLanguageProbabilities(testDataModel, languagesSequence))
            val languageKeys = allProbabilities.last().keys

            if (languageKeys.isNotEmpty()) {
                languagesSequence = languagesSequence.filter { it in languageKeys }
            }

            if (i == 1) countUnigramsOfInputText(unigramCountsOfInputText, testDataModel, languagesSequence)
        }

        val summedUpProbabilities = sumUpProbabilities(allProbabilities, unigramCountsOfInputText, languagesSequence)
        val highestProbability = summedUpProbabilities.maxBy { it.value }!!.value
        val confidenceValues = summedUpProbabilities.mapValues { highestProbability / it.value }

        return confidenceValues.toSortedMap(compareByDescending { confidenceValues[it] })
    }

    internal fun cleanUpInputText(text: String): String {
        return text.trim().toLowerCase()
            .replace(PUNCTUATION, "")
            .replace(NUMBERS, "")
            .replace(MULTIPLE_WHITESPACE, " ")
    }

    internal fun splitTextIntoWords(text: String): List<String> {
        return if (text.contains(' ')) {
            text.split(' ')
        } else {
            listOf(text)
        }
    }

    internal fun addLanguageModel(language: Language) {
        languages.add(language)
        if (!unigramLanguageModels.containsKey(language)) {
            unigramLanguageModels[language] = lazy { loadLanguageModel(language, ngramLength = 1) }
            bigramLanguageModels[language] = lazy { loadLanguageModel(language, ngramLength = 2) }
            trigramLanguageModels[language] = lazy { loadLanguageModel(language, ngramLength = 3) }
            quadrigramLanguageModels[language] = lazy { loadLanguageModel(language, ngramLength = 4) }
            fivegramLanguageModels[language] = lazy { loadLanguageModel(language, ngramLength = 5) }
        }
    }

    internal fun removeLanguageModel(language: Language) {
        if (languages.contains(language)) {
            languages.remove(language)
        }
    }

    internal fun countUnigramsOfInputText(
        unigramCounts: MutableMap<Language, Int>,
        unigramLanguageModel: TestDataLanguageModel,
        languagesSequence: Sequence<Language>
    ) {
        for (language in languagesSequence) {
            for (unigram in unigramLanguageModel.ngrams) {
                val probability = lookUpNgramProbability(language, unigram)
                if (probability > 0) {
                    unigramCounts.merge(language, 1, Int::plus)
                }
            }
        }
    }

    internal fun sumUpProbabilities(
        probabilities: List<Map<Language, Double>>,
        unigramCountsOfInputText: Map<Language, Int>,
        languagesSequence: Sequence<Language>
    ): Map<Language, Double> {
        val summedUpProbabilities = hashMapOf<Language, Double>()
        for (language in languagesSequence) {
            summedUpProbabilities[language] = probabilities.sumByDouble { it[language] ?: 0.0 }

            if (unigramCountsOfInputText.containsKey(language)) {
                summedUpProbabilities[language] = summedUpProbabilities.getValue(language) / unigramCountsOfInputText.getValue(language)
            }
        }
        return summedUpProbabilities.filter { it.value != 0.0 }
    }

    internal fun detectLanguageWithRules(words: List<String>): Language {
        val totalLanguageCounts = mutableMapOf<Language, Int>()

        for (word in words) {
            val wordLanguageCounts = mutableMapOf<Language, Int>()

            for (character in word.map { it.toString() }) {
                var isMatch = false
                for ((alphabet, language) in alphabetsSupportingExactlyOneLanguage) {
                    if (alphabet.matches(character)) {
                        wordLanguageCounts.addCharCount(language)
                        isMatch = true
                    }
                }
                if (!isMatch) {
                    when {
                        Alphabet.HAN.matches(character) -> wordLanguageCounts.addCharCount(CHINESE)
                        JAPANESE_CHARACTER_SET.matches(character) -> wordLanguageCounts.addCharCount(JAPANESE)
                        Alphabet.LATIN.matches(character) ||
                            Alphabet.CYRILLIC.matches(character) ||
                            Alphabet.DEVANAGARI.matches(character) -> languagesWithUniqueCharacters.filter {
                                it.uniqueCharacters.contains(character)
                            }.forEach {
                                wordLanguageCounts.addCharCount(it)
                        }
                    }
                }
            }

            if (wordLanguageCounts.isEmpty()) {
                totalLanguageCounts.addCharCount(UNKNOWN)
            } else if (wordLanguageCounts.size == 1) {
                val language = wordLanguageCounts.toList().first().first
                if (language in languages) {
                    totalLanguageCounts.addCharCount(language)
                } else {
                    totalLanguageCounts.addCharCount(UNKNOWN)
                }
            } else {
                if (wordLanguageCounts.containsKey(CHINESE) && wordLanguageCounts.containsKey(JAPANESE)) {
                    totalLanguageCounts.addCharCount(JAPANESE)
                } else {
                    val sortedWordLanguageCounts = wordLanguageCounts.toList().sortedByDescending { it.second }
                    val (mostFrequentLanguage, firstCharCount) = sortedWordLanguageCounts[0]
                    val (_, secondCharCount) = sortedWordLanguageCounts[1]

                    if (firstCharCount > secondCharCount && mostFrequentLanguage in languages) {
                        totalLanguageCounts.addCharCount(mostFrequentLanguage)
                    } else {
                        totalLanguageCounts.addCharCount(UNKNOWN)
                    }
                }
            }
        }

        val unknownLanguageCount = totalLanguageCounts[UNKNOWN] ?: 0
        val filteredLanguageCounts = if (unknownLanguageCount >= (0.5 * words.size)) {
            totalLanguageCounts
        } else {
            totalLanguageCounts.filterNot { it.key == UNKNOWN }
        }

        if (filteredLanguageCounts.isEmpty()) {
            return UNKNOWN
        }
        if (filteredLanguageCounts.size == 1) {
            return filteredLanguageCounts.toList().first().first
        }

        val sortedTotalLanguageCounts = filteredLanguageCounts.toList().sortedByDescending { it.second }
        val (mostFrequentLanguage, firstCharCount) = sortedTotalLanguageCounts[0]
        val (_, secondCharCount) = sortedTotalLanguageCounts[1]

        if (firstCharCount == secondCharCount) {
            return UNKNOWN
        }

        return mostFrequentLanguage
    }

    internal fun filterLanguagesByRules(words: List<String>): Sequence<Language> {
        val detectedAlphabets = mutableMapOf<Alphabet, Int>()
        val alphabets = listOf(Alphabet.CYRILLIC, Alphabet.ARABIC, Alphabet.HAN, Alphabet.LATIN, Alphabet.DEVANAGARI)

        for (word in words) {
            for (alphabet in alphabets) {
                if (alphabet.matches(word)) {
                    detectedAlphabets.merge(alphabet, 1, Int::plus)
                    break
                }
            }
        }

        if (detectedAlphabets.isEmpty()) {
            return languages.asSequence()
        }

        val mostFrequentAlphabet = detectedAlphabets.entries.sortedByDescending { it.value }.first().key
        val filteredLanguages = when (mostFrequentAlphabet) {
            Alphabet.CYRILLIC -> languages.asSequence().filter { it.alphabets.contains(Alphabet.CYRILLIC) }
            Alphabet.DEVANAGARI -> languages.asSequence().filter { it.alphabets.contains(Alphabet.DEVANAGARI) }
            Alphabet.ARABIC -> languages.asSequence().filter { it.alphabets.contains(Alphabet.ARABIC) }
            Alphabet.HAN -> languages.asSequence().filter { it.alphabets.contains(Alphabet.HAN) }
            Alphabet.LATIN -> {
                if (languages.contains(NORWEGIAN)) {
                    languages.asSequence().filter { it.alphabets.contains(Alphabet.LATIN) && it !in setOf(BOKMAL, NYNORSK) }
                } else if (languages.contains(BOKMAL) || languages.contains(NYNORSK)) {
                    languages.asSequence().filter { it.alphabets.contains(Alphabet.LATIN) && it != NORWEGIAN }
                } else {
                    languages.asSequence().filter { it.alphabets.contains(Alphabet.LATIN) }
                }
            }
            else -> languages.asSequence()
        }

        val languageCounts = mutableMapOf<Language, Int>()
        for (word in words) {
            for ((characters, languages) in CHARS_TO_LANGUAGES_MAPPING) {
                if (word.containsAnyOf(characters)) {
                    for (language in languages) {
                        languageCounts.addCharCount(language)
                    }
                    break
                }
            }
        }

        val languagesSubset = languageCounts.filterValues { it >= words.size / 2 }.keys

        return if (languagesSubset.isNotEmpty()) {
            filteredLanguages.filter { it in languagesSubset }
        } else {
            filteredLanguages
        }
    }

    internal fun computeLanguageProbabilities(
        testDataModel: TestDataLanguageModel,
        languagesSequence: Sequence<Language>
    ): Map<Language, Double> {
        val probabilities = hashMapOf<Language, Double>()
        for (language in languagesSequence) {
            probabilities[language] = computeSumOfNgramProbabilities(language, testDataModel.ngrams)
        }
        return probabilities.filter { it.value < 0.0 }
    }

    internal fun computeSumOfNgramProbabilities(
        language: Language,
        ngrams: Set<Ngram>
    ): Double {
        val probabilities = mutableListOf<Double>()

        for (ngram in ngrams) {
            for (elem in ngram.rangeOfLowerOrderNgrams()) {
                val probability = lookUpNgramProbability(language, elem)
                if (probability > 0) {
                    probabilities.add(probability)
                    break
                }
            }
        }
        return probabilities.sumByDouble { ln(it) }
    }

    internal fun lookUpNgramProbability(
        language: Language,
        ngram: Ngram
    ): Double {
        val languageModels = when (ngram.value.length) {
            5 -> fivegramLanguageModels
            4 -> quadrigramLanguageModels
            3 -> trigramLanguageModels
            2 -> bigramLanguageModels
            1 -> unigramLanguageModels
            0 -> throw IllegalArgumentException("Zerogram detected")
            else -> throw IllegalArgumentException("unsupported ngram length detected: ${ngram.value.length}")
        }

        return languageModels.getValue(language).value.getRelativeFrequency(ngram)
    }

    internal fun loadLanguageModel(
        language: Language,
        ngramLength: Int
    ): TrainingDataLanguageModel {
        val fileName = "${Ngram.getNgramNameByLength(ngramLength)}s.json"
        val filePath = "/language-models/${language.isoCode639_1}/$fileName"
        val inputStream = LanguageDetector::class.java.getResourceAsStream(filePath)
        val jsonContent = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return TrainingDataLanguageModel.fromJson(jsonContent)
    }

    internal fun loadLanguageModels(ngramLength: Int): MutableMap<Language, Lazy<TrainingDataLanguageModel>> {
        val languageModels = hashMapOf<Language, Lazy<TrainingDataLanguageModel>>()
        for (language in languages) {
            languageModels[language] = lazy { loadLanguageModel(language, ngramLength) }
        }
        return languageModels
    }

    private fun MutableMap<Language, Int>.addCharCount(language: Language) {
        this.merge(language, 1, Int::plus)
    }

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is LanguageDetector -> false
        languages != other.languages -> false
        minimumRelativeDistance != other.minimumRelativeDistance -> false
        else -> true
    }

    override fun hashCode() = 31 * languages.hashCode() + minimumRelativeDistance.hashCode()

    internal companion object {
        private val NO_LETTER = Regex("^[^\\p{L}]+$")
        private val PUNCTUATION = Regex("\\p{P}")
        private val NUMBERS = Regex("\\p{N}")
        private val MULTIPLE_WHITESPACE = Regex("\\s+")
        private val JAPANESE_CHARACTER_SET = try {
            Regex("^[\\p{Hiragana}\\p{Katakana}\\p{Han}]+$")
        } catch (e: PatternSyntaxException) {
            Regex("^[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]+$")
        }

        private val CHARS_TO_LANGUAGES_MAPPING = mapOf(
            "Ćć" to setOf(BOSNIAN, CROATIAN, POLISH),
            "Đđ" to setOf(BOSNIAN, CROATIAN, VIETNAMESE),
            "Ãã" to setOf(PORTUGUESE, VIETNAMESE),
            "ĄąĘę" to setOf(LITHUANIAN, POLISH),
            "Ūū" to setOf(LATVIAN, LITHUANIAN),
            "Żż" to setOf(POLISH, ROMANIAN),
            "Îî" to setOf(FRENCH, ROMANIAN),
            "Ìì" to setOf(ITALIAN, VIETNAMESE),
            "Ññ" to setOf(BASQUE, SPANISH),
            "ŇňŤť" to setOf(CZECH, SLOVAK),
            "Ăă" to setOf(ROMANIAN, VIETNAMESE),
            "İıĞğ" to setOf(AZERBAIJANI, TURKISH),
            "ЁёЫыЭэ" to setOf(BELARUSIAN, KAZAKH, MONGOLIAN, RUSSIAN),
            "ЩщЪъ" to setOf(BULGARIAN, KAZAKH, MONGOLIAN, RUSSIAN),
            "ЈјЉљЊњ" to setOf(MACEDONIAN, SERBIAN),
            "Іі" to setOf(BELARUSIAN, KAZAKH, UKRAINIAN),

            "Şş" to setOf(AZERBAIJANI, ROMANIAN, TURKISH),
            "Ďď" to setOf(CZECH, ROMANIAN, SLOVAK),
            "ÐðÞþ" to setOf(ICELANDIC, LATVIAN, TURKISH),
            "Ûû" to setOf(FRENCH, HUNGARIAN, LATVIAN),
            "ÈèÙù" to setOf(FRENCH, ITALIAN, VIETNAMESE),

            "ЙйЬьЮюЧчЯя" to setOf(BELARUSIAN, BULGARIAN, KAZAKH, MONGOLIAN, RUSSIAN, UKRAINIAN),
            "Êê" to setOf(AFRIKAANS, FRENCH, PORTUGUESE, VIETNAMESE),
            "Õõ" to setOf(ESTONIAN, HUNGARIAN, PORTUGUESE, VIETNAMESE),
            "Òò" to setOf(CATALAN, ITALIAN, LATVIAN, VIETNAMESE),
            "Ôô" to setOf(FRENCH, PORTUGUESE, SLOVAK, VIETNAMESE),
            "Øø" to setOf(BOKMAL, DANISH, NORWEGIAN, NYNORSK),

            "Ýý" to setOf(CZECH, ICELANDIC, SLOVAK, TURKISH, VIETNAMESE),
            "Ää" to setOf(ESTONIAN, FINNISH, GERMAN, SLOVAK, SWEDISH),
            "Ââ" to setOf(LATVIAN, PORTUGUESE, ROMANIAN, TURKISH, VIETNAMESE),
            "Àà" to setOf(CATALAN, FRENCH, ITALIAN, PORTUGUESE, VIETNAMESE),
            "Ææ" to setOf(BOKMAL, DANISH, ICELANDIC, NORWEGIAN, NYNORSK),
            "Åå" to setOf(BOKMAL, DANISH, NORWEGIAN, NYNORSK, SWEDISH),

            "ČčŠšŽž" to setOf(BOSNIAN, CZECH, CROATIAN, LATVIAN, LITHUANIAN, SLOVAK, SLOVENE),
            "Üü" to setOf(AZERBAIJANI, CATALAN, ESTONIAN, GERMAN, HUNGARIAN, TURKISH),

            "Çç" to setOf(ALBANIAN, AZERBAIJANI, BASQUE, CATALAN, FRENCH, LATVIAN, PORTUGUESE, TURKISH),
            "Öö" to setOf(AZERBAIJANI, ESTONIAN, FINNISH, GERMAN, HUNGARIAN, ICELANDIC, SWEDISH, TURKISH),
            "ÁáÍíÚú" to setOf(CATALAN, CZECH, ICELANDIC, IRISH, HUNGARIAN, PORTUGUESE, SLOVAK, VIETNAMESE),
            "Óó" to setOf(CATALAN, HUNGARIAN, ICELANDIC, IRISH, POLISH, PORTUGUESE, SLOVAK, VIETNAMESE),

            "Éé" to setOf(CATALAN, CZECH, FRENCH, HUNGARIAN, ICELANDIC, IRISH, ITALIAN, PORTUGUESE, SLOVAK, VIETNAMESE)
        )
    }
}
