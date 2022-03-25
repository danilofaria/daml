// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package xbc

object Lang {

  type FnName = String

  sealed abstract class BinOp
  final case object AddOp extends BinOp
  final case object SubOp extends BinOp
  final case object LessOp extends BinOp

  sealed abstract class Exp
  final case class Num(x: Long) extends Exp
  final case class Builtin(b: BinOp, e1: Exp, e2: Exp) extends Exp
  final case class IfNot0(e1: Exp, e2: Exp, e3: Exp) extends Exp
  final case class Arg(i: Int) extends Exp
  final case class FnCall(f: FnName, arg: List[Exp]) extends Exp

  case class Program(defs: Map[FnName, Exp], main: Exp)

  def Add(e1: Exp, e2: Exp): Exp = Builtin(AddOp, e1, e2)
  def Sub(e1: Exp, e2: Exp): Exp = Builtin(SubOp, e1, e2)
  def Less(e1: Exp, e2: Exp): Exp = Builtin(LessOp, e1, e2)

  object Examples {

    def nfibProgram(size: Long): Program = {
      def nfib(e: Exp) = FnCall("nfib", List(e))
      val body: Exp = {
        val n = Arg(0)
        IfNot0(
          Less(n, Num(2)),
          Num(1),
          Add(Add(nfib(Sub(n, Num(1))), nfib(Sub(n, Num(2)))), Num(1)),
        )
      }
      Program(
        defs = Map("nfib" -> body),
        main = nfib(Num(size)),
      )
    }

    def tripProgram(i: Long): Program = {
      def loop(step: Exp, acc: Exp, i: Exp) = FnCall("loop", List(step, acc, i))
      val body: Exp = {
        val (step, acc, i) = (Arg(0), Arg(1), Arg(2))
        IfNot0(
          Less(i, Num(1)),
          acc,
          loop(step, Add(acc, step), Sub(i, Num(1))),
        )
      }
      Program(
        defs = Map("loop" -> body),
        main = loop(Num(3L), Num(0L), Num(i)),
      )
    }

  }
}
