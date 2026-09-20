import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.internal.TextListener

object MethodRunner {
    @JvmStatic fun main(args: Array<String>) {
        var failed = false
        for (selection in args) {
            val (className, methodName) = selection.split('#', limit = 2)
            println("Selected: $selection")
            val runner = JUnitCore()
            runner.addListener(TextListener(System.out))
            val result = runner.run(Request.method(Class.forName(className), methodName))
            failed = failed || !result.wasSuccessful()
        }
        kotlin.system.exitProcess(if (failed) 1 else 0)
    }
}
