// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.grazie.utils

import com.intellij.grazie.grammar.Typo
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.strong
import org.languagetool.rules.ExampleSentence
import org.languagetool.rules.IncorrectExample
import org.languagetool.rules.Rule

val Typo.isSpellingTypo: Boolean
  get() = info.rule.isDictionaryBasedSpellingRule

val ExampleSentence.text: CharSequence
  get() = example

fun Rule.toDescriptionSanitized() = this.description.replace("**", "")

private fun FlowOrPhrasingContent.toHtml(example: IncorrectExample, mistakeHandler: FlowOrPhrasingContent.(String) -> Unit) {
  Regex("(.*?)<marker>(.*?)</marker>|(.*)").findAll(example.example).forEach {
    val (prefix, mistake, suffix) = it.destructured

    +prefix
    mistakeHandler(mistake)
    +suffix
  }
}

fun FlowOrPhrasingContent.toIncorrectHtml(example: IncorrectExample) {
  toHtml(example) { mistake ->
    if (mistake.isNotEmpty()) {
      strong {
        +mistake.trim()
      }
    }
  }
}

fun FlowOrPhrasingContent.toCorrectHtml(example: IncorrectExample) {
  toHtml(example) { mistake ->
    if (mistake.isNotEmpty() && example.corrections.isNotEmpty()) {
      strong {
        +example.corrections.first().trim()
      }
    }
  }
}
