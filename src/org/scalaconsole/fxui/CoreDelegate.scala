package org.scalaconsole
package fxui
import java.io._
import javafx.concurrent.Task
import org.scalaconsole.data.ClassLoaderManager
import java.util.jar.JarFile
import scala.tools.nsc.plugins.PluginDescription
import org.scalaconsole.DetachedILoop
import scala.tools.nsc.Settings
import java.util.concurrent.ArrayBlockingQueue
import javafx.application.Platform
import org.controlsfx.dialog.Dialogs
import javafx.geometry.Orientation
import com.google.common.base.Strings
import org.scalaconsole.net.{OAuthTinyServer, Gist}

class CoreDelegate(val controller: ScalaConsoleController) {
  val commandQueue = new ArrayBlockingQueue[(Symbol, String)](10)

  // 这一对stream是从repl的输出到右侧的textarea的数据通道，不变
  val outputIs = new PipedInputStream(4096)
  val replOs   = new PipedOutputStream(outputIs)

  val sysOutErr = new PrintStream(replOs) {
    override def write(buf: Array[Byte], off: Int, len: Int) {
      val str = new String(buf, off, len)
      replOs.write(str.getBytes)
      replOs.flush()
    }
  }
  System.setOut(sysOutErr)

  def startOutputRenderer() = {
    val task = new Task[Unit] {
      override def call() = {
        for (line <- io.Source.fromInputStream(outputIs).getLines) {
          Platform.runLater(new Runnable {
            override def run() = {
              controller.outputArea.appendText(s"$line\n")
            }
          })
        }
      }
    }
    val thread = new Thread(task)
    thread.setDaemon(true)
    thread.start()
  }

  /**
   * 启动一个新的repl，用于切换scala版本和reset时。每次调用它，就生成一对stream和一个Task来运行scala ILoop
   * @return
   */
  def connectToRepl(writer: PrintWriter, pasteFunc: String => Unit) = {
    val task = new Task[Unit] {
      override def call() = {
        var quitCommand = false
        while (!isCancelled) {
          commandQueue.take() match {
            case ('Normal, script: String) =>
              writer.write(script)
              if (!script.endsWith("\n")) writer.write("\n")
              writer.flush()
              if (script == ":q")
                cancel()
            case ('Paste, script: String) =>
              println("// Interpreting in paste mode ")
              pasteFunc(script)
              println("// Exiting paste mode. ")
          }
        }
      }
    }
    val thread = new Thread(task)
    thread.setDaemon(true)
    thread.start()
  }

  def isToReader(is: InputStream) = new BufferedReader(new InputStreamReader(is))

  def osToWriter(os: OutputStream) = new PrintWriter(new OutputStreamWriter(os))

  def startRepl() = {
    def task = new Task[Unit]() {

      override def call() = {
        val replIs = new PipedInputStream(4096)
        val scriptWriter = new PrintWriter(new OutputStreamWriter(new PipedOutputStream(replIs)))
        val settings = new Settings
        Variables.commandlineOption.map(settings.processArgumentString)

        for (path <- data.DependencyManager.boundedExtraClasspath(ClassLoaderManager.currentScalaVersion)) {
          settings.classpath.append(path)
          settings.classpath.value = settings.classpath.value // set settings.classpath.isDefault to false
          // enable plugins
          if (path.endsWith(".jar")) {
            val jar = new JarFile(path)
            for (xml <- Option(jar.getEntry(Constants.PluginXML))) {
              val in = jar getInputStream xml
              val plugin = PluginDescription.fromXML(in)
              if (settings.pluginOptions.value.exists(_ == plugin.name + ":enable")) {
                settings.plugin.appendToValue(path)
              }
              in.close()
            }
          }
        }

        if (ClassLoaderManager.isOrigin) {
          settings.usejavacp.value = true
          val iloop = new DetachedILoop(isToReader(replIs), osToWriter(replOs))
          connectToRepl(scriptWriter, s => iloop.intp.interpret(s))
          iloop.process(settings)
        } else {
          val (cl, scalaBootPath) = ClassLoaderManager.forVersion(ClassLoaderManager.currentScalaVersion)
          settings.usejavacp.value = true
          settings.bootclasspath.value = scalaBootPath
          cl.asContext {
            settings.embeddedDefaults(cl)
            val remoteClazz = Class.forName("org.scalaconsole.DetachedILoop", false, cl)
            val _iloop = remoteClazz.getConstructor(classOf[java.io.BufferedReader], classOf[java.io.PrintWriter]).
                         newInstance(isToReader(replIs), osToWriter(replOs))
            connectToRepl(scriptWriter, { s =>
              val _intp = remoteClazz.getMethod("intp").invoke(_iloop)
              val remoteIMain = Class.forName("scala.tools.nsc.interpreter.IMain", false, cl)
              remoteIMain.getMethod("interpret", classOf[String]).invoke(_intp, s)
            })
            remoteClazz.getMethod("process", classOf[Array[String]]).invoke(_iloop, settings.recreateArgs.toArray)
          }
        }

        replIs.close()
        scriptWriter.close()
      }
    }
    val thread = new Thread(task)
    thread.setDaemon(true)
    thread.start()
  }

  def run(script: String) = {
    commandQueue.put('Normal, script)
  }

  def runPaste(script: String) = {
    commandQueue.put('Paste, script)
  }

  startOutputRenderer()
  startRepl()

  def setFont() = {
    val f = Variables.displayFont
    controller.outputArea.setFont(f)
    Platform.runLater(new Runnable() {
      override def run() = {
        val engine = controller.scriptArea.getEngine
        val doc = engine.getDocument
        val editor = doc.getElementById("editor")
        val css = s"font-family:${f.getFamily}; font-size: ${f.getSize}px"
        editor.setAttribute("style", css)
      }
    })
  }

  def clearScript() = {
    Platform.runLater(new Runnable() {
      override def run() = {
        controller.scriptArea.getEngine.executeScript("editor.setValue('')")
      }
    })
  }

  private def reset(cls: Boolean = true) = {
    commandQueue.put('Normal -> ":q")
    startRepl()
    if (cls) controller.outputArea.clear()
    setStatus("Repl reset.")
  }

  def setCommandlineOptions() = {
    val current = Variables.commandlineOption
    val masth = "Example: -Xprint:typer"
    val msg = s"current: ${current.getOrElse("none")}"
    val result = Dialogs.create().title("Set Commandline Options").masthead(masth).message(msg).showTextInput(current.getOrElse(""))
    if (result != null && Variables.commandlineOption.getOrElse("") != result) {
      Variables.commandlineOption = Some(result)
      reset()
    }
  }

  def onSetFont() = {
    val masth = "Example: Consolas-14"
    val msg = s"current: ${Variables.displayFont}"
    val f = Variables.displayFont
    val fontAsString = s"${f.getFamily}-${f.getSize.toInt}"
    val result = Dialogs.create().title("Set Display Font").masthead(masth).message(msg).showTextInput(fontAsString)
    if (result != null) {
      Variables.displayFont = Variables.decodeFont(result)
      setFont()
      setStatus(s"Font set to $fontAsString")
    }
  }

  def onToggleSplitterOrientation() = {
    import Orientation._
    controller.splitPane.getOrientation match {
      case HORIZONTAL =>
        controller.splitPane.setOrientation(VERTICAL)
      case VERTICAL =>
        controller.splitPane.setOrientation(HORIZONTAL)
    }
  }

  def resetRepl() = reset()

  private def setStatus(s: String) = Platform.runLater(new Runnable() {
    override def run() = {
      controller.statusBar.setText(s)
    }
  })

  private def postGist(token: Option[String]) = {
    val code = controller.scriptArea.getEngine.executeScript("editor.getValue()").toString
    val description = Dialogs.create().title("Gist Description").masthead(null).showTextInput()
    if (!Strings.isNullOrEmpty(code)) {
      setStatus("Posting to gist...")
      val task = new Task[Unit] {
        override def call() = {
          val msg = Gist.post(code, token, description)
          setStatus(msg)
        }
      }
      val thread = new Thread(task)
      thread.setDaemon(true)
      thread.start()
    } else {
      setStatus("Empty Content. Not posting.")
    }
  }

  def postAnonymousGist() = postGist(None)

  def postGistWithAccount() = OAuthTinyServer.withAccessToken(postGist)
}
