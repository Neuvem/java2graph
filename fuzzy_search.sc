import io.shiftleft.semanticcpg.language._

@main def main(cpgFile: String) = {
    val cpg = importCpg(cpgFile).get
    
    val targets = List("addLogger", "getConfiguration", "writeNullField", "writeStringField", "publishEvent", "tryPublishEvent")
    
    println("Target|JoernFullName|NumArgs")
    targets.foreach { t =>
        cpg.call.nameExact(t).foreach { c =>
            val args = c.argument.l
            println(s"$t|${c.methodFullName}|${args.size}")
        }
    }
}
