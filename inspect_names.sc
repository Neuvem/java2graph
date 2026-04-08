import io.shiftleft.semanticcpg.language._

@main def main(cpgFile: String) = {
    val cpg = importCpg(cpgFile).get
    
    println("MethodFullName")
    cpg.call.take(100).methodFullName.toSet.toList.sorted.foreach(println)
    
    println("\nSearching for IOException.getMessage")
    cpg.call.methodFullName(".*IOException.*getMessage.*").l.foreach { c =>
        println(s"Name: ${c.name}, FullName: ${c.methodFullName}")
    }
}
