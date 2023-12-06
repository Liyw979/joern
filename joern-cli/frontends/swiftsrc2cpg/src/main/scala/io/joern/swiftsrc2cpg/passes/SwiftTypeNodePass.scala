package io.joern.swiftsrc2cpg.passes

import io.shiftleft.codepropertygraph.Cpg
import io.joern.x2cpg.passes.frontend.TypeNodePass
import io.shiftleft.semanticcpg.language._
import io.shiftleft.passes.KeyPool

import scala.collection.mutable

object SwiftTypeNodePass {

  def withRegisteredTypes(registeredTypes: List[String], cpg: Cpg, keyPool: Option[KeyPool] = None): TypeNodePass = {
    new TypeNodePass(registeredTypes, cpg, keyPool, getTypesFromCpg = false) {

      override protected def typeDeclTypes: mutable.Set[String] = {
        // The only difference to the default implementation in TypeNodePass.typeDeclTypes is the following:
        // We do not want to add types for types being inherited as this is already handled by the SwiftInheritanceNamePass.
        val typeDeclTypes = mutable.Set[String]()
        cpg.typeDecl.foreach { typeDecl =>
          typeDeclTypes += typeDecl.fullName
        }
        typeDeclTypes
      }

    }
  }

}