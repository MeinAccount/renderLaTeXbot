import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    ApiContextInitializer.init()

    val api = TelegramBotsApi()
    api.registerBot(LaTeXBot())
}

private val threadPool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() - 1, ::TempDirThread)

class TempDirThread(target: Runnable) : Thread(target) {
    val tmpDir = createTempDir()
    val latexFile = File(tmpDir, "main.tex")
    val outputFile = File(tmpDir, "main.png")
}


private val template = LaTeXBot::class.java.getResource("/template.tex").readText()

data class ProcessingJob(val bot: TelegramLongPollingBot, val message: Message, val code: String) : Runnable {
    override fun run() {
        val thread = Thread.currentThread() as TempDirThread
        thread.latexFile.writer().use {
            it.write(template)
            it.write(code)
            it.write("\n\\end{document}")
        }

        try {
            val compilation = ProcessBuilder("pdflatex", "-interaction=nonstopmode", thread.latexFile.name)
                    .directory(thread.tmpDir)
                    .inheritIO().start()
            bot.execute(SendChatAction(message.chatId, "upload_photo"))
            compilation.waitFor(20, TimeUnit.SECONDS)

            if (compilation.exitValue() == 0) {
                val convert = ProcessBuilder("convert", "-density", "300", "main.pdf", "-quality", "90", "main.png")
                        .directory(thread.tmpDir)
                        .inheritIO().start()
                bot.execute(SendChatAction(message.chatId, "upload_photo"))

                convert.waitFor(10, TimeUnit.SECONDS)
                bot.execute(SendPhoto()
                        .setChatId(message.chatId)
                        .setPhoto(thread.outputFile)
                        .setReplyToMessageId(message.messageId))
            }
        } catch (e: InterruptedException) {
        }
    }
}


class LaTeXBot : TelegramLongPollingBot() {
    private val userWhitelist = System.getenv("USER_WHITELIST").split(",").map { it.toInt() }.toSet()

    override fun onUpdateReceived(update: Update) {
        println(update)
        if (update.hasMessage() && update.message.text != null && !update.message.text.isEmpty() &&
                userWhitelist.contains(update.message.from.id)) {
            if (update.message.text.startsWith("/tex@RenderLaTeXBot ")) {
                threadPool.submit(ProcessingJob(this, update.message, update.message.text.drop(20)))
            } else if (update.message.text.startsWith("/tex ")) {
                threadPool.submit(ProcessingJob(this, update.message, update.message.text.drop(5)))
            } else if (update.message.text.startsWith("@RenderLaTeXBot ")) {
                threadPool.submit(ProcessingJob(this, update.message, update.message.text.drop(16)))
            } else if (update.message.isUserMessage || update.message.text.contains("\\")
                    || update.message.text.contains("$")) {
                threadPool.submit(ProcessingJob(this, update.message, update.message.text))
            }
        }
    }

    override fun getBotToken() = System.getenv("BOT_TOKEN")

    override fun getBotUsername() = "RenderLaTeXBot"
}
