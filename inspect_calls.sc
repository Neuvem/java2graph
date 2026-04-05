import io.shiftleft.codepropertygraph.generated.nodes.Call
import io.shiftleft.semanticcpg.language._

@main def main(cpgPath: String): Unit = {
  loadCpg(cpgPath)
  println(s"Total calls: ${cpg.call.l.length}")
  println("Sample calls (first 50):")
  cpg.call.take(50).l.foreach { c =>
    println(s"Name: ${c.name}, FullName: ${c.methodFullName}, Code: ${c.code}")
  }
}
