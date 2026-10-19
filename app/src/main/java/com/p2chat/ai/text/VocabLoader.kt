package com.p2chat.ai.text

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Loads BERT/WordPiece vocabulary file (vocab.txt) into memory (AI-3, T3.7).
 */
object VocabLoader {

    /**
     * Reads vocabulary from [inputStream], mapping each token string to its 0-based line index.
     */
    fun loadVocab(inputStream: InputStream): Map<String, Int> {
        val vocab = HashMap<String, Int>(32000)
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            var index = 0
            while (reader.readLine().also { line = it } != null) {
                val token = line!!.trimEnd('\r', '\n')
                vocab[token] = index++
            }
        }
        return vocab
    }
}
