/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.brandonli.mcav.gradle

import java.util.Locale
import java.util.Objects

/**
 * One fuzz test method: a method annotated with Jazzer's `@FuzzTest` in a test class.
 *
 * @property className  the binary name of the test class, such as `me.brandonli.mcav.vnc.RfbGuardFuzzTest`
 * @property methodName the name of the method
 */
class FuzzTarget(val className: String, val methodName: String) {

    private companion object {

        const val PACKAGE_SEPARATOR: Char = '.'

        const val TASK_PREFIX: String = "fuzz"
    }

    /**
     * Returns the test filter pattern that selects only this method.
     *
     * @return the pattern, the class name and the method name joined by a dot
     */
    fun filterPattern(): String {
        return "${this.className}$PACKAGE_SEPARATOR${this.methodName}"
    }

    /**
     * Returns the name of the task that fuzzes only this method, such as `fuzzRfbGuardFuzzTestShouldCheckAHandshake`.
     *
     * @return the task name
     */
    fun taskName(): String {
        val simpleName: String = this.className.substringAfterLast(PACKAGE_SEPARATOR)
        val firstLetter: String = this.methodName.take(1).uppercase(Locale.ROOT)
        val otherLetters: String = this.methodName.drop(1)
        val capitalizedMethod: String = "$firstLetter$otherLetters"
        return "$TASK_PREFIX$simpleName$capitalizedMethod"
    }

    /**
     * Returns whether [other] is a fuzz target of the same class and method.
     *
     * @param other the object to compare with
     * @return whether both names match
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        if (other !is FuzzTarget) {
            return false
        }

        return this.className == other.className && this.methodName == other.methodName
    }

    /**
     * Returns a hash code built from both names.
     *
     * @return the hash code
     */
    override fun hashCode(): Int {
        return Objects.hash(this.className, this.methodName)
    }

    /**
     * Returns the filter pattern of this target.
     *
     * @return the representation
     */
    override fun toString(): String {
        return this.filterPattern()
    }
}
