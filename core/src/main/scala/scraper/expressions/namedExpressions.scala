package scraper.expressions

import java.util.concurrent.atomic.AtomicLong

import scala.language.higherKinds
import scala.util.{Success, Try}
import scalaz.Scalaz._
import scalaz._

import scraper.Row
import scraper.exceptions.{ExpressionUnresolvedException, ResolutionFailureException}
import scraper.expressions.NamedExpression.newExpressionID
import scraper.types._
import scraper.utils._

case class ExpressionID(id: Long)

trait NamedExpression extends Expression {
  def name: String

  def expressionID: ExpressionID

  def toAttribute: Attribute
}

trait GeneratedNamedExpression extends NamedExpression

trait GeneratedAlias extends GeneratedNamedExpression with UnaryExpression

trait GeneratedAttribute
  extends GeneratedNamedExpression
  with ResolvedAttribute
  with UnevaluableExpression

trait UnresolvedNamedExpression extends UnresolvedExpression with NamedExpression {
  override def expressionID: ExpressionID = throw new ExpressionUnresolvedException(this)
}

object NamedExpression {
  private val currentID = new AtomicLong(0L)

  private val AnonymousColumnName = "?column?"

  def newExpressionID(): ExpressionID = ExpressionID(currentID.getAndIncrement())

  def unapply(e: NamedExpression): Option[(String, DataType)] = Some((e.name, e.dataType))

  /**
   * Auxiliary class only used for removing back-ticks from auto-generated column names.  For
   * example, for expression `id + 1`, we'd like to generate column name `(id + 1)` instead of
   * `(&#96;id&#96; + 1)`.
   */
  case class UnquotedAttribute(named: Attribute) extends LeafExpression with UnevaluableExpression {
    override def resolved: Boolean = named.resolved

    override def dataType: DataType = named.dataType

    override def nullable: Boolean = named.nullable

    override def sql: Try[String] = Try(named.name)
  }

  // TODO Replace this with `UnresolvedAlias`
  def named(expression: Expression): NamedExpression = {
    def rewrite(e: Expression): Expression =
      e.transformDown { case a: Attribute => UnquotedAttribute(a) }

    expression match {
      case e: NamedExpression => e
      case e                  => e as (rewrite(e).sql getOrElse AnonymousColumnName)
    }
  }
}

case object Star extends LeafExpression with UnresolvedNamedExpression {
  override def name: String = throw new ExpressionUnresolvedException(this)

  override def toAttribute: Attribute = throw new ExpressionUnresolvedException(this)

  override protected def template[T[_]: Applicative](f: (Expression) => T[String]): T[String] =
    implicitly[Applicative[T]] point "*"
}

case class Alias(
  child: Expression,
  name: String,
  override val expressionID: ExpressionID = newExpressionID()
) extends NamedExpression with UnaryExpression {
  override def foldable: Boolean = false

  override protected def strictDataType: DataType = child.dataType

  override def evaluate(input: Row): Any = child.evaluate(input)

  override lazy val toAttribute: Attribute = if (child.resolved) {
    AttributeRef(name, child.dataType, child.nullable, expressionID)
  } else {
    UnresolvedAttribute(name)
  }

  override def debugString: String = s"${child.debugString} AS ${quote(name)}#${expressionID.id}"

  override def sql: Try[String] = child.sql map (childSQL => s"$childSQL AS ${quote(name)}")
}

case class GroupingAlias(
  child: Expression,
  override val expressionID: ExpressionID = newExpressionID()
) extends GeneratedAlias {
  override def name: String = GroupingAlias.Prefix + expressionID.id

  override def toAttribute: Attribute =
    GroupingAttribute(child.dataType, child.nullable, expressionID)

  override protected def template[T[_]: Applicative](f: (Expression) => T[String]): T[String] =
    f(child) map (childString => s"$childString AS ${quote(name)}")
}

object GroupingAlias {
  val Prefix = "group$"
}

trait Attribute extends NamedExpression with LeafExpression {
  override def foldable: Boolean = false

  override lazy val references: Set[Attribute] = Set(this)

  override def toAttribute: Attribute = this

  def newInstance(): Attribute

  def withNullability(nullability: Boolean): Attribute

  def ? : Attribute = withNullability(true)

  def ! : Attribute = withNullability(false)
}

case class UnresolvedAttribute(name: String, qualifier: Option[String] = None)
  extends Attribute with UnresolvedNamedExpression {

  override protected def template[T[_]: Applicative](f: (Expression) => T[String]): T[String] =
    implicitly[Applicative[T]].point {
      (qualifier.toSeq :+ name) map quote mkString "."
    }

  override def newInstance(): Attribute = this

  override def withNullability(nullability: Boolean): Attribute = this

  def of(dataType: DataType): AttributeRef =
    AttributeRef(name, dataType, nullable = true, newExpressionID())

  def boolean: AttributeRef = this of BooleanType

  def byte: AttributeRef = this of ByteType

  def short: AttributeRef = this of ShortType

  def long: AttributeRef = this of LongType

  def int: AttributeRef = this of IntType

  def float: AttributeRef = this of FloatType

  def double: AttributeRef = this of DoubleType

  def string: AttributeRef = this of StringType
}

trait ResolvedAttribute extends Attribute {
  override def debugString: String = {
    val nullability = if (nullable) "?" else "!"
    s"${quote(name)}#${expressionID.id}:${dataType.sql}$nullability"
  }

  override def sql: Try[String] = Success(s"${quote(name)}")

  def at(ordinal: Int): BoundRef = BoundRef(ordinal, dataType, nullable)
}

object ResolvedAttribute {
  def intersectByID(lhs: Set[Attribute], rhs: Set[Attribute]): Set[Attribute] = {
    require(lhs.forall(_.resolved) && rhs.forall(_.resolved))
    lhs filter (a => rhs exists (_.expressionID == a.expressionID))
  }
}

case class AttributeRef(
  name: String,
  override val dataType: DataType,
  override val nullable: Boolean,
  override val expressionID: ExpressionID,
  qualifier: Option[String] = None
) extends ResolvedAttribute with UnevaluableExpression {

  override def newInstance(): Attribute = copy(expressionID = NamedExpression.newExpressionID())

  override def ? : AttributeRef = withNullability(true)

  override def ! : AttributeRef = withNullability(false)

  override def withNullability(nullable: Boolean): AttributeRef = copy(nullable = nullable)

  override def debugString: String = ((qualifier.toSeq map quote) :+ super.debugString) mkString "."
}

case class GroupingAttribute(
  override val dataType: DataType,
  override val nullable: Boolean,
  override val expressionID: ExpressionID
) extends GeneratedAttribute {
  override def newInstance(): Attribute = copy(expressionID = NamedExpression.newExpressionID())

  override def withNullability(nullable: Boolean): GroupingAttribute = copy(nullable = nullable)

  override val name: String = GroupingAlias.Prefix + expressionID.id
}

case class BoundRef(ordinal: Int, override val dataType: DataType, override val nullable: Boolean)
  extends NamedExpression with LeafExpression with NonSQLExpression {

  override val name: String = s"input[$ordinal]"

  override def toAttribute: Attribute = throw new UnsupportedOperationException

  override def expressionID: ExpressionID = throw new UnsupportedOperationException

  override def evaluate(input: Row): Any = input(ordinal)

  override def debugString: String = {
    val nullability = if (nullable) "?" else "!"
    name + ":" + dataType.sql + nullability
  }
}

object BoundRef {
  def bind[A <: Expression](expression: A, input: Seq[Attribute]): A = {
    expression.transformUp {
      case ref: ResolvedAttribute =>
        val ordinal = input.indexWhere(_.expressionID == ref.expressionID)
        if (ordinal == -1) {
          throw new ResolutionFailureException({
            val inputAttributes = input.map(_.nodeCaption).mkString(", ")
            s"Failed to bind attribute reference $ref to any input attributes: $inputAttributes"
          })
        } else {
          BoundRef(ordinal, ref.dataType, ref.nullable)
        }
    }.asInstanceOf[A]
  }
}
