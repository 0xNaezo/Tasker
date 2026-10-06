package app.tasker.lint.detekt

import org.jetbrains.kotlin.psi.KtFile

/**
 * File name without directories. detekt names the files it parses by their absolute path, so comparing
 * [KtFile.getName] with an allow-list of plain file names never matches.
 */
internal val KtFile.baseName: String get() = name.substringAfterLast('/').substringAfterLast('\\')
