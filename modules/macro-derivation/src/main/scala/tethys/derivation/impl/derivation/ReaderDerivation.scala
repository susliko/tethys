package tethys.derivation.impl.derivation

import tethys.JsonReader
import tethys.derivation.builder.ReaderDescription.Field.RawField
import tethys.derivation.impl.builder.ReaderBuilderUtils
import tethys.derivation.impl.{BaseMacroDefinitions, CaseClassUtils}
import tethys.readers.{FieldName, JsonReaderDefaultValue}
import tethys.readers.tokens.TokenIterator

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.macros.blackbox

trait ReaderDerivation
  extends BaseMacroDefinitions
    with CaseClassUtils
    with DerivationUtils
    with ReaderBuilderUtils {
  val c: blackbox.Context
  import c.universe._

  private val fieldNameTerm = TermName(c.freshName("fieldName"))
  private val fieldNameType = tq"${weakTypeOf[FieldName]}"
  private val fieldNameTmp = TermName(c.freshName("fieldNameTmp"))

  private val tokenIteratorTerm = TermName(c.freshName("it"))
  private val tokenIteratorType = tq"${typeOf[TokenIterator]}"

  private val readerErrorCompanion = q"$readersPack.ReaderError"
  private val primitiveReadersCompanion = q"$readersPack.instances.PrimitiveReaders"

  private val jsonReaderDefaultValueType = tq"$readersPack.JsonReaderDefaultValue"
  private val jsonReaderType = tq"$tethysPack.JsonReader"
  private val somethingChanged = TermName(c.freshName("somethingChanged"))



  private sealed trait ReaderField
  private case class SimpleField(name: String,
                                 tpe: Type,
                                 jsonName: String,
                                 value: TermName,
                                 isInitialized: TermName) extends ReaderField

  private case class ExtractedField(name: String,
                                    tpe: Type,
                                    functionName: TermName,
                                    args: List[FunctionArgument],
                                    body: Tree,
                                    value: TermName,
                                    isInitialized: TermName) extends ReaderField

  private case class FromExtractedReader(name: String,
                                         tpe: Type,
                                         jsonName: String,
                                         functionName: TermName,
                                         args: List[FunctionArgument],
                                         body: Tree,
                                         value: TermName,
                                         isInitialized: TermName,
                                         tempIterator: TermName) extends ReaderField

  private case class FunctionArgument(field: Field, value: TermName, isInitialized: TermName)


  def deriveReader2[A: WeakTypeTag](description: ReaderMacroDescription): Expr[JsonReader[A]] = {
    val tpe = weakTypeOf[A]
    val classDef = caseClassDefinition(tpe)

    val readerFields: List[ReaderField] = classDef.fields.map { field =>
      SimpleField(
        name = field.name,
        tpe = field.tpe,
        jsonName = field.name,
        value = TermName(c.freshName(field.name + "Value")),
        isInitialized = TermName(c.freshName(field.name + "Init"))
      )
    }

    val alteredFields = applyDescription(readerFields, description)

    val (typeReaders, readerTrees) = allocateReaders(readerFields)
    val (typeDefaultValues, defauleValuesTrees) = allocateDefaultValues(readerFields)
    val (readerVariables, readerVariablesTree) = allocateReaderVariablesBlock(readerFields, typeDefaultValues)
    val (jsonVariables, jsonVariablesTree) = allocateJsonVariablesBlock(readerFields, typeDefaultValues)

    ???
  }

  private def applyDescription(readerFields: List[ReaderField], description: ReaderMacroDescription): List[ReaderField] = {
    def mapField(fields: List[ReaderField], name: String)(f: SimpleField => ReaderField): List[ReaderField] = {
      fields.map {
        case field: SimpleField if field.name == name => f(field)
        case field => field
      }
    }

    def buildArgument(field: Field, readerFields: List[ReaderField]): FunctionArgument = {


      field match {
        case Field.ClassField(name, _) =>
          readerFields.collectFirst {
            case f: SimpleField if f.name == name =>
              FunctionArgument(field, f.value, f.isInitialized)
            case f: ExtractedField if f.name == name =>
              FunctionArgument(field, f.value, f.isInitialized)
            case f: FromExtractedReader if f.name == name =>
              FunctionArgument(field, f.value, f.isInitialized)
          }.head
        case Field.RawField(name, tpe) =>
          readerFields.flatMap {
            case f: SimpleField if f.jsonName == name && f.tpe =:= tpe =>
              List(FunctionArgument(field, f.value, f.isInitialized))
            case f: ExtractedField =>
              f.args.collectFirst {
                case FunctionArgument(rf: RawField, value, isInitialized)
              }
            case f: FromExtractedReader if f.name == name =>
              FunctionArgument(field, f.value, f.isInitialized)
            case _ =>
              Nil
          }

          ???
      }

    }

    description.operations.foldLeft(readerFields) {
      case (fields, operation) =>
        operation match {
          case ReaderMacroOperation.ExtractFieldAs(field, tpe, as, fun) =>
            mapField(fields, field)(f => ExtractedField(
              name = field,
              tpe = tpe,
              functionName = TermName(c.freshName(field + "Fun")),
              args = List(Field.RawField(field, as)),
              body = fun,
              value = f.value,
              isInitialized = f.isInitialized
            ))

          case ReaderMacroOperation.ExtractFieldValue(field, from, fun) =>
            mapField(fields, field)(f => ExtractedField(
              name = field,
              tpe = f.tpe,
              functionName = TermName(c.freshName(field + "Fun")),
              args = from.toList,
              body = fun
            ))
          case ReaderMacroOperation.ExtractFieldReader(field, from, fun) =>
            mapField(fields, field)(f => FromExtractedReader(
              name = field,
              tpe = f.tpe,
              jsonName = f.jsonName,
              functionName = TermName(c.freshName(field + "JsonFun")),
              args = from.toList,
              body = fun
            ))
        }
    }
  }

  private def allocateReaders(readerFields: List[ReaderField]): (List[(Type, TermName)], List[Tree]) = {
    val jsonTypes = readerFields.flatMap {
      case SimpleField(_, tpe, _) =>
        List(tpe)
      case ExtractedField(_, _, _, args, _) =>
        args.map(_.tpe)
      case FromExtractedReader(_, _, _, _, args, _) =>
        args.map(_.tpe)
    }

    jsonTypes.foldLeft((List[(Type, TermName)](), List[Tree]())) {
      case ((types, trees), tpe) if !types.exists(_._1 =:= tpe) =>
        val term = TermName(c.freshName())
        val reader = {
          if (tpe =:= typeOf[Short]) q"private[this] val $term = $primitiveReadersCompanion.ShortJsonReader"
          else if (tpe =:= typeOf[Int]) q"private[this] val $term = $primitiveReadersCompanion.IntJsonReader"
          else if (tpe =:= typeOf[Long]) q"private[this] val $term = $primitiveReadersCompanion.LongJsonReader"
          else if (tpe =:= typeOf[Float]) q"private[this] val $term = $primitiveReadersCompanion.FloatJsonReader"
          else if (tpe =:= typeOf[Double]) q"private[this] val $term = $primitiveReadersCompanion.DoubleJsonReader"
          else if (tpe =:= typeOf[Boolean]) q"private[this] val $term = $primitiveReadersCompanion.BooleanJsonReader"
          else q"private[this] lazy val $term = implicitly[$jsonReaderType[$tpe]]"
        }
        (tpe -> term :: types, reader :: trees)

      case (res, _) => res
    }
  }

  private def allocateDefaultValues(readerFields: List[ReaderField]): (List[(Type, TermName)], List[Tree]) = {
    val allTypes = readerFields.flatMap {
      case SimpleField(_, tpe, _) =>
        List(tpe)
      case ExtractedField(_, tpe, _, args, _) =>
        tpe :: args.map(_.tpe)
      case FromExtractedReader(_, tpe, _, _, args, _) =>
        tpe :: args.map(_.tpe)
    }
    allTypes.foldLeft((List[(Type, TermName)](), List[Tree]())) {
      case ((types, trees), tpe) if !types.exists(_._1 =:= tpe) =>
        val term = TermName(c.freshName())
        val default = q"private[this] var $term: $tpe = _"

        (tpe -> term :: types, default :: trees)

      case (res, _) => res
    }
  }

  private case class ReaderFieldVars(name: String, value: TermName, isInitialized: TermName, treeIterator: Option[TermName])
  private def allocateReaderVariablesBlock(readerFields: List[ReaderField], typeDefaultValues: List[(Type, TermName)]): (List[ReaderFieldVars], List[Tree]) = {
    readerFields.foldLeft(List[ReaderFieldVars](), List[Tree]()) {
      case ((vars, trees), field) =>
        val fieldVars = ReaderFieldVars(
         name = field.name,
         value = TermName(c.freshName(field.name + "Field")),
         isInitialized = TermName(c.freshName(field.name + "Init")),
         treeIterator = field.customJsonReader.map(_ => TermName(c.freshName(field.name + "Tree")))
        )

        val tree = q"var ${fieldVars.value}: ${field.tpe} = ${typeDefaultValues.find(_._1 =:= field.tpe).get}" ::
          q"var ${fieldVars.isInitialized}: Boolean = false" ::
          fieldVars.treeIterator.map(term => q"var $term: $tokenIteratorType = null").toList


        (fieldVars :: vars, tree ::: trees)
    }
  }

  private case class JsonFieldVars(name: String, tpe: Type, value: TermName, isInitialized: TermName)
  private def allocateJsonVariablesBlock(readerFields: List[ReaderField], typeDefaultValues: List[(Type, TermName)]): (List[JsonFieldVars], List[Tree]) = {
    readerFields.flatMap(_.jsonFields).foldLeft(List[JsonFieldVars](), List[Tree]()) {
      case ((vars, trees), field) if !vars.exists(f => f.name == field.name && f.tpe =:= field.tpe) =>
        val fieldVars = JsonFieldVars(
         name = field.name,
         tpe = field.tpe,
         value = TermName(c.freshName(field.name + "FieldJson")),
         isInitialized = TermName(c.freshName(field.name + "InitJson"))
        )

        val tree = q"var ${fieldVars.value}: ${field.tpe} = ${typeDefaultValues.find(_._1 =:= field.tpe).get}" ::
          q"var ${fieldVars.isInitialized}: Boolean = false" ::
          Nil


        (fieldVars :: vars, tree ::: trees)

      case (res, _) => res
    }
  }

  private def allocateFieldCases(readerFields: List[ReaderField], )

  def deriveReader[A: WeakTypeTag]: Expr[JsonReader[A]] = {
    deriveReader(ReaderMacroDescription(Seq()))
  }

  def deriveReader[A: WeakTypeTag](description: ReaderMacroDescription): Expr[JsonReader[A]] = {
    val tpe = weakTypeOf[A]
    val classDef = caseClassDefinition(tpe)
    implicit val context: ReaderContext = new ReaderContext

    val name = TermName(c.freshName("name"))

    val definitions = classDef.fields.map(field => FieldDefinitions(field.name, field.tpe))
    val syntheticDefinitions = extractSyntheticDefinitions(description, classDef)
    val transformedDefinitions = transformDefinitions(description, definitions)

    val allDefinitions = transformedDefinitions ++ syntheticDefinitions
    allDefinitions.foreach(context.addDefinition)

    val vars = allDefinitions.flatMap(_.definitions)
    val cases = allDefinitions.flatMap(_.fieldCase) :+ cq"_ => $tokenIteratorTerm.skipExpression()"
    val isAllInitialized: Tree = {
      val trees = transformedDefinitions.map(d => q"${d.isInitialized}")
      if(trees.size < 2) trees.headOption.getOrElse(q"true")
      else trees.reduceLeft[Tree] {
        case (left, right) => q"$left && $right"
      }
    }

    val defaultValues =
      q"""
          ..${syntheticDefinitions.flatMap(_.defaultValueExtraction)}

          ..${transformedDefinitions.filter(_.extractionType == Direct).flatMap(_.defaultValueExtraction)}
       """

    val transformations = {
      if(!transformedDefinitions.exists(_.extractionType != Direct)) EmptyTree
      else
        q"""
           var $somethingChanged = true
           while($somethingChanged) {
             $somethingChanged = false
             ..${transformedDefinitions.filter(_.extractionType != Direct).map(_.transformation)}
           }

           ..${transformedDefinitions.filter(_.extractionType == FromFields).map(_.defaultValueExtraction)}
         """
    }

    val uninitializedFieldsReason: Tree = {
      val uninitializedFields = TermName(c.freshName("uninitializedFields"))
      val fields = transformedDefinitions.map(d => q"""if(!${d.isInitialized}) $uninitializedFields += ${d.name} """)
      q"""
         val $uninitializedFields = scala.collection.mutable.ArrayBuffer.empty[String]
         ..$fields
         "Can not extract fields " + $uninitializedFields.mkString("'", "', '", "'")
       """
    }

    c.Expr[JsonReader[A]] {
      c.untypecheck {
        q"""
           new $jsonReaderType[$tpe] {
              ..${context.defaultValues}

              ${provideThisReaderImplicit(tpe)}

              ..${context.readers}

              ..${context.functions}

              override def read($tokenIteratorTerm: $tokenIteratorType)(implicit $fieldNameTerm: $fieldNameType): $tpe = {
                if(!$tokenIteratorTerm.currentToken().isObjectStart) $readerErrorCompanion.wrongJson("Expected object start but found: "  + $tokenIteratorTerm.currentToken().toString)
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

                  $defaultValues

                  $transformations

                  if(!($isAllInitialized)) $readerErrorCompanion.wrongJson($uninitializedFieldsReason)
                  else new ${weakTypeOf[A]}(..${transformedDefinitions.map(_.value)})
                }
              }
           }: $jsonReaderType[$tpe]
        """
      }
    }
  }

  private def extractSyntheticDefinitions(description: ReaderMacroDescription, classDef: CaseClassDefinition)
                                         (implicit context: ReaderContext): Seq[FieldDefinitions] = {
    val names = classDef.fields.map(_.name).toSet

    val fields: Seq[Field] = description.operations.flatMap {
      case _: ReaderMacroOperation.ExtractFieldAs =>
        Seq.empty

      case ReaderMacroOperation.ExtractFieldValue(_, fs, _) =>
        fs.filterNot(f => names(f.name))

      case ReaderMacroOperation.ExtractFieldReader(_, fs, _) =>
        fs.filterNot(f => names(f.name))
    }

    fields.map(f => f.name -> f.tpe).toMap.toSeq.map {
      case (name, tpe) => FieldDefinitions(name, tpe)
    }
  }

  private def transformDefinitions(description: ReaderMacroDescription, definitions: Seq[FieldDefinitions])
                                  (implicit context: ReaderContext): Seq[FieldDefinitions] = {
    val fieldDesctiptions = description.operations.map(f => f.field -> f).toMap

    definitions.map(d => fieldDesctiptions.get(d.name).fold(d) {
      case op: ReaderMacroOperation.ExtractFieldAs =>
        d.copy(extractionType = Direct, transformer = Some(op))

      case op: ReaderMacroOperation.ExtractFieldValue =>
        d.copy(extractionType = FromFields, transformer = Some(op))

      case op: ReaderMacroOperation.ExtractFieldReader=>
        d.copy(extractionType = FromExtractedReader, transformer = Some(op))
    })
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
    private val funs: mutable.ArrayBuffer[(TermName, Tree)] = mutable.ArrayBuffer[(TermName, Tree)]()
    private val definitions: mutable.Map[String, FieldDefinitions] = mutable.Map[String, FieldDefinitions]()

    def provideReader(tpe: Type): TermName = {
      readersMapping.getOrElseUpdate(unwrapType(tpe), TermName(c.freshName("reader")))
    }

    def provideDefaultValue(tpe: Type): TermName = {
      defaultValuesMapping.getOrElseUpdate(unwrapType(tpe), TermName(c.freshName("defaultValue")))
    }

    def registerFunction(fun: Tree): TermName = {
      funs.find(_._2 eq fun) match {
        case Some((name, _)) => name
        case _ =>
          val name = TermName(c.freshName("fun"))
          funs += name -> fun
          name
      }
    }

    def addDefinition(d: FieldDefinitions): Unit = definitions += d.name -> d

    def definition(name: String): FieldDefinitions = definitions(name)

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

    def functions: Seq[Tree] = funs.toList.map {
      case (name, fun) => q"private[this] val $name = $fun"
    }

    @tailrec
    private def unwrapType(tpe: Type): Type = tpe match {
      case ConstantType(const) => unwrapType(const.tpe)
      case _ => tpe
    }
  }

  sealed trait ExtractionType
  case object Direct extends ExtractionType
  case object FromFields extends ExtractionType
  case object FromExtractedReader extends ExtractionType

  protected case class FieldDefinitions(name: String,
                                        tpe: Type,
                                        extractionType: ExtractionType = Direct,
                                        transformer: Option[ReaderMacroOperation] = None)
                                       (implicit readerContext: ReaderContext) {
    lazy val value: TermName = TermName(c.freshName(name + "Field"))
    lazy val valueTree: TermName = TermName(c.freshName(name + "FieldTree"))
    lazy val isInitialized: TermName = TermName(c.freshName(name + "FieldInitialized"))
    lazy val defaultValue: TermName = TermName(c.freshName("defaultValue"))

    def definitions: List[Tree] = {
      val defs = q"""
         {
           var $value: $tpe = ${readerContext.provideDefaultValue(tpe)}
           var $isInitialized: Boolean = false
         }
       """.children

      if(extractionType == FromExtractedReader) defs :+ q"var $valueTree: $tokenIteratorType = null"
      else defs
    }

    def defaultValueExtraction: Option[Tree] = {
      val (valueTpe, valueFun) = transformer match {
        case Some(op: ReaderMacroOperation.ExtractFieldAs) =>
          (op.as, (defaultValue: Tree) => q"${readerContext.registerFunction(op.fun)}.apply($defaultValue.asInstanceOf[${op.as}])")
        case _ =>
          (tpe, (defaultValue: Tree) => q"$defaultValue.asInstanceOf[$tpe]")
      }
      extractDefaultValue(valueTpe).map { defaultValue =>
        q"""
           if(!$isInitialized) {
              $value = ${valueFun(defaultValue)}
              $isInitialized = true
           }
         """
      }
    }

    def fieldCase: Option[CaseDef] = transformer match {
      case Some(op: ReaderMacroOperation.ExtractFieldAs) =>
        val caseDef = cq"""
          $name =>
            $value = ${readerContext.registerFunction(op.fun)}.apply(${readerContext.provideReader(op.as)}.read($tokenIteratorTerm))
            $isInitialized = true
        """
        Some(caseDef)

      case Some(op: ReaderMacroOperation.ExtractFieldReader) =>
        val caseDef = cq"""
          $name =>
            $valueTree = $tokenIteratorTerm.collectExpression()
        """
        Some(caseDef)

      case None =>
        val caseDef = cq"""
          $name =>
            $value = ${readerContext.provideReader(tpe)}.read($tokenIteratorTerm)
            $isInitialized = true
        """
        Some(caseDef)

      case _ =>
        None
    }

    def transformation: Tree = transformer.fold(EmptyTree) {
      case op: ReaderMacroOperation.ExtractFieldValue =>
        val canTransform = op.from.map(f => readerContext.definition(f.name).isInitialized)
          .foldLeft[Tree](q"!$isInitialized") {
          case (current, next) => q"$current && $next"
        }

        val args = op.from.map(f => readerContext.definition(f.name).value)

        q"""
           if($canTransform) {
              implicit val $fieldNameTerm: $fieldNameType = $fieldNameTmp.appendFieldName($name)
              $value = ${readerContext.registerFunction(op.fun)}.apply(..$args)
              $isInitialized = true
              $somethingChanged = true
           }
         """

      case op: ReaderMacroOperation.ExtractFieldReader =>
        val canTransform = op.from.map(f => readerContext.definition(f.name).isInitialized)
          .foldLeft[Tree](q"!$isInitialized") {
            case (current, next) => q"$current && $next"
          }

        val args = op.from.map(f => readerContext.definition(f.name).value)
        val reader = TermName(c.freshName(name + "Reader"))
        extractDefaultValue(tpe) match {
          case Some(defaultValue) =>
            q"""
             if($canTransform) {
                val $reader = ${readerContext.registerFunction(op.fun)}.apply(..$args)
                if($valueTree != null) {
                  implicit val $fieldNameTerm: $fieldNameType = $fieldNameTmp.appendFieldName($name)
                  $value = $reader.read($valueTree)
                  $isInitialized = true
                  $somethingChanged = true
                } else {
                  $value = $defaultValue.asInstanceOf[$tpe]
                  $isInitialized = true
                }
             }
           """
          case None =>
            q"""
             if($canTransform && $valueTree != null) {
                val $reader = ${readerContext.registerFunction(op.fun)}.apply(..$args)
                implicit val $fieldNameTerm: $fieldNameType = $fieldNameTmp.appendFieldName($name)
                $value = $reader.read($valueTree)
                $isInitialized = true
                $somethingChanged = true
             }
           """
        }


      case _ =>
        EmptyTree
    }

    def extractDefaultValue(tpe: Type): Option[Tree] = {
      c.typecheck(q"implicitly[$jsonReaderDefaultValueType[$tpe]]") match {
        case q"$_.implicitly[$_]($defaultValue)" =>
          val mbValue = defaultValue.tpe.typeSymbol.annotations.map(_.tree).collectFirst {
            case q"new $clazz(${value: Tree})" if clazz.tpe =:= typeOf[JsonReaderDefaultValue.ReaderDefaultValue] =>
              value
          }

          mbValue match {
            case None =>
              abort(s"JsonReaderDefaultValue '${defaultValue.tpe}' is not annotated with 'ReaderDefaultValue'")
            case Some(q"null") =>
              None
            case Some(value) =>
              Some(value)
          }

      }
    }

  }
}
