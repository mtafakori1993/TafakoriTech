package ir.tafakoritech.adminapplication

import ir.tafakoritech.adminapplication.ui.server.ClaudePrompt
import ir.tafakoritech.adminapplication.ui.server.detectClaudePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudePromptTest {

    @Test
    fun detectsModelSelectMenu() {
        val screen = """
            ╭─────────────────────────────────────────╮
            │ Select model                            │
            │ Switch between Claude models            │
            │                                         │
            │ ❯ 1. Default (recommended)              │
            │   2. Opus                               │
            │   3. Sonnet                             │
            │                                         │
            ╰─────────────────────────────────────────╯
        """.trimIndent()

        val prompt = detectClaudePrompt(screen) as ClaudePrompt.Options
        assertTrue(prompt.question.contains("Select model"))
        assertEquals(3, prompt.options.size)
        assertEquals("Opus", prompt.options[1].label)
        assertTrue(prompt.options[0].selected)
    }

    @Test
    fun detectsPermissionPrompt() {
        val screen = """
            │ Bash command                                  │
            │   rm -rf build                                │
            │                                               │
            │ Do you want to proceed?                       │
            │ ❯ 1. Yes                                      │
            │   2. Yes, and don't ask again                 │
            │   3. No, and tell Claude what to do (esc)     │
        """.trimIndent()

        val prompt = detectClaudePrompt(screen) as ClaudePrompt.Options
        assertEquals("Do you want to proceed?", prompt.question)
        assertEquals(3, prompt.options.size)
    }

    @Test
    fun numberedListInProseIsNotAMenu() {
        // No ❯ cursor — this is Claude writing a list, not a select menu.
        val screen = """
            ● Here is the plan:
              1. Update the parser
              2. Add tests
              3. Refactor the client
        """.trimIndent()
        assertNull(detectClaudePrompt(screen))
    }

    @Test
    fun detectsFreeTextQuestion() {
        val screen = """
            ● I finished the refactor. Should I also update
              the integration tests to match?

            ╭───────────────────────────────────────────╮
            │ >                                         │
            ╰───────────────────────────────────────────╯
        """.trimIndent()

        val prompt = detectClaudePrompt(screen) as ClaudePrompt.Question
        assertTrue(prompt.text.endsWith("tests to match?"))
        assertTrue(prompt.text.startsWith("I finished"))
    }

    @Test
    fun noQuestionWhileClaudeIsWorking() {
        val screen = """
            ● Should I continue with this approach?
            ✻ Pondering… (esc to interrupt)
            ╭───────────────────────────────────────────╮
            │ >                                         │
            ╰───────────────────────────────────────────╯
        """.trimIndent()
        assertNull(detectClaudePrompt(screen))
    }

    @Test
    fun statementAboveInputIsNotAQuestion() {
        val screen = """
            ● Done. All tests pass.

            ╭───────────────────────────────────────────╮
            │ >                                         │
            ╰───────────────────────────────────────────╯
        """.trimIndent()
        assertNull(detectClaudePrompt(screen))
    }
}
