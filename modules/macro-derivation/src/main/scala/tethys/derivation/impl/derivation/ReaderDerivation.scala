package tethys.derivation.impl.derivation

import tethys.JsonReader
import tethys.derivation.impl.{BaseMacroDefinitions, CaseClassUtils}
import tethys.readers.{FieldName, ReaderError}
import tethys.readers.tokens.TokenIterator

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.macros.blackbox

trait ReaderDerivation
  extends BaseMacroDefinitions
    with CaseClassUtils
    with DerivationUtils {
  val c: blackbox.Context
  import c.universe._

  private val fieldNameTerm = TermName(c.freshName("fieldName"))
  private val fieldNameType = tq"${weakTypeOf[FieldName]}"

  private val tokenIteratorTerm = TermName(c.freshName("it"))
  private val tokenIteratorType = tq"${typeOf[TokenIterator]}"

  private val readerErrorCompanion = q"$readersPack.ReaderError"
  private val primitiveReadersCompanion = q"$readersPack.instances.PrimitiveReaders"

  private val jsonReaderType = tq"$tethysPack.JsonReader"

  def deriveReader[A: WeakTypeTag]: Expr[JsonReader[A]] = {
    val tpe = weakTypeOf[A]
    val classDef = caseClassDefinition(tpe)
    implicit val context: ReaderContext = new ReaderContext

    val name = TermName(c.freshName("name"))
    val fieldNameTmp = TermName(c.freshName("fieldNameTmp"))

    val definitions = classDef.fields.map(field => new FieldDefinitions(field.name, field.tpe))

    val vars = definitions.flatMap(_.definitions)
    val cases = definitions.map(_.fieldCase) :+ cq"_ => $tokenIteratorTerm.skipExpression()"
    val isAllInitialized: Tree = {
      val trees = definitions.map(d => q"${d.isInitialized}")
      if(trees.size < 2) trees.headOption.getOrElse(q"true")
      else trees.reduceLeft[Tree] {
        case (left, right) => q"$left && $right"
      }
    }

    c.Expr[JsonReader[A]] {
      c.untypecheck {
        q"""
           new $jsonReaderType[$tpe] {
              ..${context.defaultValues}

              ${provideThisReaderImplicit(tpe)}

              ..${context.readers}

              override def read($tokenIteratorTerm: $tokenIteratorType)(implicit $fieldNameTerm: $fieldNameType): $tpe = {
                if(!$tokenIteratorTerm.currentToken().isObjectStart) $readerErrorCompanion.wrongType[$tpe]
                else {
                  val $fieldNameTmp = $fieldNameTerm
                  $tokenIteratorTerm.nextToken()
                  ..$vars

                  while(!$tokenIteratorTerm.currentToken().isObjectEnd) {
                    val $name = $tokenIteratorTerm.fieldName()
                    $tokenIteratorTerm.nextToken()
                    implicit val $fieldNameTerm: $fieldNameType = $fieldNameTmp.appendFieldName($name)
                    $name match { case ..$cases }
                  }
                  $tokenIteratorTerm.nextToken()

                  if(!($isAllInitialized)) $readerErrorCompanion.wrongJson
                  else new ${weakTypeOf[A]}(..${definitions.map(_.value)})
                }
              }
           }: $jsonReaderType[$tpe]
        """
      }
    }
  }

  private def provideThisReaderImplicit(tpe: Type): Tree = {
    c.typecheck(q"implicitly[$jsonReaderType[$tpe]]", silent = true) match {
      case EmptyTree =>
        val thisWriterTerm = TermName(c.freshName("thisWriter"))
        q"implicit private[this] def $thisWriterTerm: $jsonReaderType[$tpe] = this"
      case _ => EmptyTree
    }
  }

  protected class ReaderContext {
    private val readersMapping: mutable.Map[Type, TermName] = mutable.Map[Type, TermName]()
    private val defaultValuesMapping: mutable.Map[Type, TermName] = mutable.Map[Type, TermName]()

    def provideReader(tpe: Type): TermName = {
      readersMapping.getOrElseUpdate(unwrapType(tpe), TermName(c.freshName("reader")))
    }

    def provideDefaultValue(tpe: Type): TermName = {
      defaultValuesMapping.getOrElseUpdate(unwrapType(tpe), TermName(c.freshName("defaultValue")))
    }

    def readers: Seq[Tree] = readersMapping.map {
      case (tpe, name) if tpe =:= typeOf[Short] =>
        q"private[this] val $name = $primitiveReadersCompanion.ShortJsonReader"
      case (tpe, name) if tpe =:= typeOf[Int] =>
        q"private[this] val $name = $primitiveReadersCompanion.IntJsonReader"
      case (tpe, name) if tpe =:= typeOf[Long] =>
        q"private[this] val $name = $primitiveReadersCompanion.LongJsonReader"
      case (tpe, name) if tpe =:= typeOf[Float] =>
        q"private[this] val $name = $primitiveReadersCompanion.FloatJsonReader"
      case (tpe, name) if tpe =:= typeOf[Double] =>
        q"private[this] val $name = $primitiveReadersCompanion.DoubleJsonReader"
      case (tpe, name) if tpe =:= typeOf[Boolean] =>
        q"private[this] val $name = $primitiveReadersCompanion.BooleanJsonReader"

      case (tpe, name) =>
        q"private[this] lazy val $name = implicitly[$jsonReaderType[$tpe]]"
    }.toSeq

    def defaultValues: Seq[Tree] = defaultValuesMapping.map {
      case (tpe, name) =>
        q"private[this] var $name: $tpe = _"
    }.toSeq

    @tailrec
    private def unwrapType(tpe: Type): Type = tpe match {
      case ConstantType(const) => unwrapType(const.tpe)
      case _ => tpe
    }
  }

  protected class FieldDefinitions(name: String, tpe: Type)(implicit readerContext: ReaderContext) {
    val value: TermName = TermName(c.freshName(name + "Field"))
    val isInitialized: TermName = TermName(c.freshName(name + "FieldInitialized"))
    val defaultValue: TermName = TermName(c.freshName("defaultValue"))

    def definitions: List[Tree] = {
      q"""
         {
           var $value: $tpe = ${readerContext.provideDefaultValue(tpe)}
           var $isInitialized: Boolean = false
           val $defaultValue: Option[$tpe] = ${readerContext.provideReader(tpe)}.defaultValue
           if($defaultValue.nonEmpty) {
              $value = $defaultValue.get
              $isInitialized = true
           }
         }
       """.children
    }

    def fieldCase: CaseDef = {
      cq"""
          $name =>
            $value = ${readerContext.provideReader(tpe)}.read($tokenIteratorTerm)
            $isInitialized = true
        """
    }
  }
}
