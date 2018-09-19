import org.telegram.telegrambots.ApiContextInitializer
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
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

data class ProcessingJob(val bot: TelegramLongPollingBot, val message: Message,
                         val code: String, val report: Boolean = false) : Runnable {
    override fun run() {
        val thread = Thread.currentThread() as TempDirThread
        thread.latexFile.writer().use {
            it.write(template)
            it.write(code)
            it.write("\n\\end{document}")
        }


        val compilation = ProcessBuilder("pdflatex", "-interaction=nonstopmode", thread.latexFile.name)
                .directory(thread.tmpDir).start()
        bot.execute(SendChatAction(message.chatId, "upload_photo"))

        try {
            compilation.waitFor(20, TimeUnit.SECONDS)

            if (compilation.exitValue() == 0) {
                val convert = ProcessBuilder("convert", "-density", "300", "main.pdf", "-quality", "90", "main.png")
                        .directory(thread.tmpDir).start()
                bot.execute(SendChatAction(message.chatId, "upload_photo"))

                convert.waitFor(10, TimeUnit.SECONDS)
                bot.execute(SendPhoto()
                        .setChatId(message.chatId)
                        .setPhoto(thread.outputFile)
                        .setReplyToMessageId(message.messageId))
            }
        } catch (e: InterruptedException) {
        }

        if (report) {
            bot.execute(SendChatAction(message.chatId, "upload_document"))
            bot.execute(SendDocument()
                    .setChatId(message.chatId)
                    .setDocument("output log", compilation.inputStream)
                    .setReplyToMessageId(message.messageId))
        }
    }
}


class LaTeXBot : TelegramLongPollingBot() {
    private val userWhitelist = System.getenv("USER_WHITELIST").split(",").map { it.toInt() }.toSet()

    override fun onUpdateReceived(update: Update) {
        println(update)
        if (update.hasMessage() && update.message.text != null && userWhitelist.contains(update.message.from.id)) {
            processMessage(update.message, force = update.message.isUserMessage)
        }
    }

    private fun processMessage(message: Message, report: Boolean = false, force: Boolean = false) {
        when {
            message.text.startsWith("/tex@RenderLaTeXBot") -> processStripped(message, message.text.drop(19))
            message.text.startsWith("/tex") -> processStripped(message, message.text.drop(4))
            message.text.startsWith("/report@RenderLaTeXBot") -> processStripped(message, message.text.drop(22), true)
            message.text.startsWith("/report") -> processStripped(message, message.text.drop(7), true)
            message.text.startsWith("@RenderLaTeXBot") -> processStripped(message, message.text.drop(15))

            (force || message.text.contains("\\") || message.text.contains("$")) && message.text.isNotBlank() ->
                threadPool.submit(ProcessingJob(this, message, message.text, report))
        }
    }

    private fun processStripped(message: Message, code: String, report: Boolean = false) {
        if (code.isNotBlank()) {
            threadPool.submit(ProcessingJob(this, message, code, report))
        } else if (message.isReply && message.replyToMessage.text != null) {
            processMessage(message.replyToMessage, report, force = true)
        }
    }


    override fun getBotToken() = System.getenv("BOT_TOKEN")

    override fun getBotUsername() = "RenderLaTeXBot"
}
