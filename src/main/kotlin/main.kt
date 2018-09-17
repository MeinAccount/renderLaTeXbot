import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.launch
import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import java.io.File
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    ApiContextInitializer.init()

    val api = TelegramBotsApi()
    api.registerBot(LaTeXBot())
}


class LaTeXBot : TelegramLongPollingBot() {
    private val tmpDir = createTempDir()
    private val latexFile = File(tmpDir, "main.tex")
    private val outputFile = File(tmpDir, "main.png")

    private val template = LaTeXBot::class.java.getResource("/template.tex").readText()
    private val userWhitelist = System.getenv("USER_WHITELIST").split(",").map { it.toInt() }.toSet()

    override fun onUpdateReceived(update: Update) {
        println(update)
        if (update.hasMessage() && update.message.text != null && !update.message.text.isEmpty() &&
                userWhitelist.contains(update.message.from.id)) {
            if (update.message.text.startsWith("/tex@RenderLaTeXBot ")) {
                process(update.message, update.message.text.drop(20))
            } else if (update.message.text.startsWith("/tex ")) {
                process(update.message, update.message.text.drop(5))
            } else if (update.message.text.startsWith("@RenderLaTeXBot ")) {
                process(update.message, update.message.text.drop(16))
            } else if (update.message.isUserMessage || update.message.text.contains("\\")
                    || update.message.text.contains("$")) {
                process(update.message, update.message.text)
            }
        }
    }

    private fun process(message: Message, code: String) {
        GlobalScope.launch {
            execute(SendChatAction(message.chatId, "upload_photo"))
        }

        latexFile.writer().use {
            it.write(template)
            it.write(code)
            it.write("\n\\end{document}")
        }

        try {
            val compilation = ProcessBuilder("pdflatex", "-interaction=nonstopmode", latexFile.name)
                    .directory(tmpDir)
                    .inheritIO().start()
            compilation.waitFor(20, TimeUnit.SECONDS)

            if (compilation.exitValue() == 0) {
                ProcessBuilder("convert", "-density", "300", "main.pdf", "-quality", "90", "main.png")
                        .directory(tmpDir)
                        .inheritIO().start()
                        .waitFor(10, TimeUnit.SECONDS)

                execute(SendPhoto()
                        .setChatId(message.chatId)
                        .setPhoto(outputFile)
                        .setReplyToMessageId(message.messageId))
            }
        } catch (e: InterruptedException) {
        }
    }

    override fun getBotToken() = System.getenv("BOT_TOKEN")

    override fun getBotUsername() = "RenderLaTeXBot"
}
