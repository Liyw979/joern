package io.joern.rubysrc2cpg.astcreation

import io.joern.rubysrc2cpg.parser.ParserAst
import io.joern.rubysrc2cpg.parser.ParserAst.*
import io.joern.rubysrc2cpg.passes.Defines
import io.joern.rubysrc2cpg.passes.Defines.getBuiltInType
import io.joern.x2cpg.datastructures.Stack.*
import io.joern.x2cpg.{Ast, ValidationMode}
import io.shiftleft.codepropertygraph.generated.ControlStructureTypes
import org.antlr.v4.runtime.ParserRuleContext

trait AstForStatementsCreator(implicit withSchemaValidation: ValidationMode) { this: AstCreator =>

  protected def astsForStatement(ctx: ParserRuleContext): Seq[Ast] = astsForStatement(ParserAst(ctx))

  protected def astsForStatement(node: ParserNode): Seq[Ast] = node match
    case node: WhileExpression            => astForWhileStatement(node) :: Nil
    case node: UntilExpression            => astForUntilStatement(node) :: Nil
    case node: IfExpression               => astForIfStatement(node) :: Nil
    case node: UnlessExpression           => astForUnlessStatement(node) :: Nil
    case node: StatementList              => astForStatementList(node) :: Nil
    case node: SimpleCallWithBlock        => astForSimpleCallWithBlock(node) :: Nil
    case node: MemberCallWithBlock        => astForMemberCallWithBlock(node) :: Nil
    case node: ReturnExpression           => astForReturnStatement(node) :: Nil
    case node: ModuleDeclaration          => astForModuleDeclaration(node) :: Nil
    case node: ClassDeclaration           => astForClassDeclaration(node) :: Nil
    case node: FieldsDeclaration          => astsForFieldDeclarations(node)
    case node: MethodDeclaration          => astForMethodDeclaration(node) :: Nil
    case node: SingletonMethodDeclaration => astForSingletonMethodDeclaration(node) :: Nil
    case _                                => astForExpression(node) :: Nil

  protected def astForWhileStatement(node: WhileExpression): Ast = {
    val conditionAst = astForExpression(node.condition)
    val bodyAsts     = astsForStatement(node.body)
    whileAst(Some(conditionAst), bodyAsts)
  }

  // `until T do B` is lowered as `while !T do B`
  protected def astForUntilStatement(node: UntilExpression): Ast = {
    val notCondition = astForExpression(UnaryExpression(node.condition, "!", node.condition))
    val bodyAsts     = astsForStatement(node.body)
    whileAst(Some(notCondition), bodyAsts)
  }

  protected def astForIfStatement(node: IfExpression): Ast = {
    val conditionAst = astForExpression(node.condition)
    val thenAst = ParserAst(node.thenClause) match
      case stmtList: StatementList => astForStatementList(stmtList)
      case _                       => astForStatementList(StatementList(node.thenClause, List(node.thenClause)))
    val elseAsts = node.elsifClauses match
      case Nil =>
        node.elseClause
          .map(ctx =>
            ParserAst(ctx) match
              case node: ElseClause => astForElseClause(node)
              case node =>
                logger.warn(s"Expecting else clause in ${code(node)} ($relativeFileName), skipping")
                astForUnknown(node)
          )
          .toList
      case elsIfCtx :: rest =>
        ParserAst(elsIfCtx) match
          case elsIfNode: ElsIfClause =>
            val newIf = IfExpression(elsIfNode.ctx, elsIfNode.condition, elsIfNode.thenClause, rest, node.elseClause)
            val wrappingBlock = blockNode(elsIfNode)
            val wrappedAst    = Ast(wrappingBlock).withChild(astForIfStatement(newIf))
            wrappedAst :: Nil
          case elsIfNode =>
            logger.warn(s"Expecting elsif clause in ${elsIfNode.text} ($relativeFileName), skipping")
            Nil
    val ifNode = controlStructureNode(node, ControlStructureTypes.IF, code(node))
    controlStructureAst(ifNode, Some(conditionAst), thenAst :: elseAsts)
  }

  private def astForElseClause(node: ElseClause): Ast = {
    ParserAst(node.thenClause) match
      case stmtList: StatementList => astForStatementList(stmtList)
      case node =>
        logger.warn(s"Expecting statement list in ${code(node)} ($relativeFileName), skipping")
        astForUnknown(node)
  }

  // `unless T do B` is lowered as `if !T then B`
  protected def astForUnlessStatement(node: UnlessExpression): Ast = {
    val notConditionAst = astForExpression(UnaryExpression(node.condition, "!", node.condition))
    val thenAst = ParserAst(node.trueBranch) match
      case stmtList: StatementList => astForStatementList(stmtList)
      case _                       => astForStatementList(StatementList(node.trueBranch, List(node.trueBranch)))
    val elseAsts = node.falseBranch
      .map(ctx =>
        ParserAst(ctx) match
          case elseNode: ElseClause => astForElseClause(elseNode)
          case elseNode =>
            logger.warn(s"Expecting else clause in ${code(node)} ($relativeFileName), skipping")
            astForUnknown(elseNode)
      )
      .toList
    val ifNode = controlStructureNode(node, ControlStructureTypes.IF, code(node))
    controlStructureAst(ifNode, Some(notConditionAst), thenAst :: elseAsts)
  }

  protected def astForStatementList(node: StatementList): Ast = {
    val block = blockNode(node)
    scope.pushNewScope(block)
    val statementAsts = node.statements.flatMap(astsForStatement)
    scope.popScope()
    blockAst(block, statementAsts)
  }

  /* `foo(<args>) do <params> <stmts> end` is lowered as a BLOCK node shaped like so:
   * ```
   * {
   *   <params> = foo(<args>)
   *   <stmts>
   * }
   * ```
   * If <params> is empty, we simply exclude the initial assignment (but keep the invocation)
   * TODO: this representation is not final. A better one is to more closely resemble Ruby's semantics
   *  and pass in the block (a closure) as an argument to `foo`, i.e. `foo(<args>, <block>)`.
   */
  protected def astForSimpleCallWithBlock(node: SimpleCallWithBlock): Ast = {
    val rubyBlock   = ParserAst(node.block).asInstanceOf[Block]
    val blockParams = rubyBlock.parameters
    if (blockParams.nonEmpty) {
      logger.warn(s"Blocks with parameters are not supported yet: ${code(node)} ($relativeFileName), skipping")
      astForUnknown(node)
    } else {
      val outerBlock = blockNode(node)
      val callAst    = astForSimpleCall(node.withoutBlock)
      methodAstParentStack.push(outerBlock)
      scope.pushNewScope(outerBlock)
      val stmtAsts = ParserAst(rubyBlock.body) match
        case stmtList: StatementList => stmtList.statements.flatMap(astsForStatement)
        case body =>
          logger.warn(s"Non-linear method bodies are not supported yet: ${body.text} ($relativeFileName), skippipg")
          astForUnknown(body) :: Nil
      scope.popScope()
      methodAstParentStack.pop()
      blockAst(outerBlock, callAst :: stmtAsts)
    }
  }

  protected def astForMemberCallWithBlock(node: MemberCallWithBlock): Ast = {
    val rubyBlock   = ParserAst(node.block).asInstanceOf[Block]
    val blockParams = rubyBlock.parameters
    if (blockParams.nonEmpty) {
      logger.warn(s"Blocks with parameters are not supported yet: ${code(node)}, skipping")
      astForUnknown(node)
    } else {
      val outerBlock = blockNode(node)
      val callAst    = astForMemberCall(node.withoutBlock)
      methodAstParentStack.push(outerBlock)
      scope.pushNewScope(outerBlock)
      val stmtAsts = ParserAst(rubyBlock.body) match
        case stmtList: StatementList => stmtList.statements.flatMap(astsForStatement)
        case body =>
          logger.warn(s"Non-linear method bodies are not supported yet: ${body.text}, skipping")
          astForUnknown(body) :: Nil
      scope.popScope()
      methodAstParentStack.pop()
      blockAst(outerBlock, callAst :: stmtAsts)
    }
  }

  protected def astForReturnStatement(node: ReturnExpression): Ast = {
    val argumentAsts = node.expressions.map(astForExpression)
    val returnNode_  = returnNode(node, code(node))
    returnAst(returnNode_, argumentAsts)
  }

  protected def astForStatementListReturningLastExpression(node: StatementList): Ast = {
    val block = blockNode(node)
    scope.pushNewScope(block)

    val stmtAsts = node.statements.size match
      case 0 => List()
      case n =>
        val (headStmts, lastStmt) = node.statements.splitAt(n - 1)
        headStmts.flatMap(astsForStatement) ++ lastStmt.flatMap(astsForImplicitReturnStatement)

    scope.popScope()
    blockAst(block, stmtAsts)
  }

  private def astsForImplicitReturnStatement(ctx: ParserRuleContext): List[Ast] = {
    ParserAst(ctx) match
      case _: (ArrayLiteral | HashLiteral | StaticLiteral | BinaryExpression | UnaryExpression | SimpleIdentifier) =>
        astForReturnStatement(ReturnExpression(ctx, List(ctx))) :: Nil
      case node: SingleAssignment =>
        astForSingleAssignment(node) :: List(astForReturnStatement(ReturnExpression(ctx, List(node.lhs))))
      case node: AttributeAssignment =>
        List(
          astForAttributeAssignment(node),
          astForReturnFieldAccess(MemberAccess(node.ctx, node.target, node.op, node.attributeName))
        )
      case node: MemberAccess    => astForReturnMemberCall(node) :: Nil
      case ret: ReturnExpression => astForReturnStatement(ret) :: Nil
      case node: MethodDeclaration =>
        List(astForMethodDeclaration(node), astForReturnMethodDeclarationSymbolName(node))
      case node =>
        logger.warn(
          s"Implicit return here not supported yet: ${ctx.getText} (${node.getClass.getSimpleName}), skipping"
        )
        List()
  }

  protected def astForReturnFieldAccess(node: MemberAccess): Ast = {
    returnAst(returnNode(node, code(node)), List(astForFieldAccess(node)))
  }

  // The evaluation of a MethodDeclaration returns its name in symbol form.
  // E.g. `def f = 0` ===> `:f`
  private def astForReturnMethodDeclarationSymbolName(node: MethodDeclaration): Ast = {
    val literalNode_ = literalNode(node, s":${node.methodName}", getBuiltInType(Defines.Symbol))
    val returnNode_  = returnNode(node, literalNode_.code)
    returnAst(returnNode_, Seq(Ast(literalNode_)))
  }

  protected def astForReturnMemberCall(node: MemberAccess): Ast = {
    returnAst(returnNode(node, code(node)), List(astForMemberAccess(node)))
  }
}
