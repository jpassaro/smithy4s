/*
 *  Copyright 2021-2024 Disney Streaming
 *
 *  Licensed under the Tomorrow Open Source Technology License, Version 1.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     https://disneystreaming.github.io/TOST-1.0.txt
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package smithy4s

import smithy.api.Length
import smithy.api.Pattern
import smithy.api.Range

/**
   * Given a constraint of type C, an RefinementProvider can produce a Refinement that
   * allows to go from A to B.
   *
   * A RefinementProvider can be used as a typeclass.
   */
trait RefinementProvider[C, A, B] { self =>
  def tag: ShapeTag[C]
  def make(c: C): Refinement.Aux[C, A, B]
  def imapFull[A0, B0](
      bijectSource: Bijection[A, A0],
      bijectTarget: Bijection[B, B0]
  ): RefinementProvider[C, A0, B0] =
    new RefinementProvider[C, A0, B0] {
      def tag = self.tag
      def make(c: C): Refinement[A0, B0] { type Constraint = C } =
        self.make(c).imapFull(bijectSource, bijectTarget)
    }
}

object RefinementProvider extends LowPriorityImplicits {

  type Simple[C, A] = RefinementProvider[C, A, A]

  implicit val stringLengthConstraint: Simple[Length, String] =
    new LengthConstraint[String](_.length)

  implicit val blobLengthConstraint: Simple[Length, Blob] =
    new LengthConstraint[Blob](_.size)

  implicit def iterableLengthConstraint[C[_], A](implicit
      ev: C[A] <:< Iterable[A]
  ): Simple[Length, C[A]] =
    new LengthConstraint[C[A]](ca => ev(ca).size)

  implicit def mapLengthConstraint[K, V]: Simple[Length, Map[K, V]] =
    new LengthConstraint[Map[K, V]](_.size)

  implicit val stringPatternConstraints: Simple[Pattern, String] =
    new PatternConstraint[String](identity)

  implicit def numericRangeConstraints[N: Numeric]
      : Simple[smithy.api.Range, N] = new RangeConstraint[N, N](identity[N])

  // Lazy to avoid some pernicious recursive initialisation issue between
  // the ShapeId static object and the generated code that makes use of it,
  // as the `IdRef` type is referenced here.
  //
  // The problem only occurs in JS/Native.
  lazy implicit val idRefRefinement
      : RefinementProvider[smithy.api.IdRef, String, ShapeId] =
    Refinement.drivenBy[smithy.api.IdRef](
      ShapeId.parse(_: String) match {
        case None        => Left("Invalid ShapeId")
        case Some(value) => Right(value)
      },
      (_: ShapeId).show
    )

  private[smithy4s] abstract class SimpleImpl[C, A](implicit _tag: ShapeTag[C])
      extends RefinementProvider[C, A, A] {

    val tag: ShapeTag[C] = _tag

    def get(c: C): A => Either[String, Unit]

    final def make(c: C): Refinement.Aux[C, A, A] = new Refinement[A, A] {
      type Constraint = C
      final val tag: ShapeTag[C] = _tag
      final val constraint: C = c
      final val run = get(c)
      final def apply(a: A): Either[String, A] = run(a).map(_ => a)
      final def unsafe(a: A): A = a
      final def from(a: A): A = a
    }
    final def from(c: C): A => A = identity[A]

  }

  private[smithy4s] class LengthConstraint[A](getLength: A => Int)
      extends SimpleImpl[Length, A] {

    def get(lengthHint: Length): A => Either[String, Unit] = { (a: A) =>
      val length = getLength(a)
      (lengthHint.min, lengthHint.max) match {
        case (Some(min), Some(max)) =>
          if (length >= min && length <= max) Right(())
          else
            Left(
              s"length required to be >= $min and <= $max, but was $length"
            )
        case (Some(min), None) =>
          if (length >= min) Right(())
          else
            Left(
              s"length required to be >= $min, but was $length"
            )
        case (None, Some(max)) =>
          if (length <= max) Right(())
          else
            Left(
              s"length required to be <= $max, but was $length"
            )
        case (None, None) => Right(())
      }
    }
  }

  private[smithy4s] class PatternConstraint[E](getValue: E => String)
      extends SimpleImpl[Pattern, E] {

    def get(pattern: Pattern): E => Either[String, Unit] = {
      val regex = pattern.value.r
      (input: E) => {
        val value = getValue(input)
        if (regex.findFirstIn(getValue(input)).isDefined) Right(())
        else
          Left(
            s"String '$value' does not match pattern '${pattern.value}'"
          )
      }
    }
  }

  private[smithy4s] class RangeConstraint[A, N: Numeric](getValue: A => N)
      extends SimpleImpl[Range, A] {
    def get(
        range: smithy.api.Range
    ): A => Either[String, Unit] = {
      val N = implicitly[Numeric[N]]

      (a: A) =>
        val value = BigDecimal(N.toDouble(getValue(a)))
        (range.min, range.max) match {
          case (Some(min), Some(max)) =>
            if (value >= min && value <= max) Right(())
            else
              Left(
                s"Input must be >= $min and <= $max, but was $value"
              )
          case (None, Some(max)) =>
            if (value <= max) Right(())
            else
              Left(
                s"Input must be <= $max, but was $value"
              )
          case (Some(min), None) =>
            if (value >= min) Right(())
            else
              Left(
                s"Input must be >= $min, but was $value"
              )
          case (None, None) => Right(())
        }
    }
  }

}

private[smithy4s] trait LowPriorityImplicits {

  implicit def enumLengthConstraint[E <: Enumeration.Value]
      : RefinementProvider[Length, E, E] =
    new RefinementProvider.LengthConstraint[E](e => e.value.size)

  implicit def enumRangeConstraint[E <: Enumeration.Value]
      : RefinementProvider[Range, E, E] =
    new RefinementProvider.RangeConstraint[E, Int](e => e.intValue)

  implicit def enumPatternConstraint[E <: Enumeration.Value]
      : RefinementProvider[Pattern, E, E] =
    new RefinementProvider.PatternConstraint[E](e => e.value)

  implicit def isomorphismConstraint[C, A, A0](implicit
      constraintOnA: RefinementProvider.Simple[C, A],
      iso: Bijection[A, A0]
  ): RefinementProvider[C, A0, A0] = constraintOnA.imapFull[A0, A0](iso, iso)

}
