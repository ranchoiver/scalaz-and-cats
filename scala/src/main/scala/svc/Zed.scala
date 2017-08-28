package svc

import scalaz._
import scalaz.effect._  /* Requires a separate dep, scalaz-effect */
import scalaz.syntax.monad._ /* Brings in `>>` operator */
import scalaz.Free.Trampoline
import scalaz.syntax.std.option._  /* Brings in `.some` */
import scalaz.std.option._  /* Brings in `|@|` for Option */

// --- //

object Zed {

  /* --- STATE --- */

  /** No extra `value` call necessary like in cats.
    *
    * Note: (TODO: What is State an alias for?)
    */
  def oneGet: (Int, Int) = State.get.run(1)

  /** We can't get rid of the explicit type param here or in cats. */
  def bind: State[Int, Unit] = State.get[Int] >>= { n => State.put(n + 1) }

  def flatmap: State[Int, Unit] = State.get.flatMap(n => State.put(n + 1))

  /** While this works fine in cats, in Scalaz this code blows the JVM stack
    * even at low inital State values (1000).
    *
    * Scalaz opted for `state` instead of `pure`, but kept Haskell's `put`.
    */
  def badCountdown: State[Int, Int] = State.get.flatMap({ n =>
    if (n <= 0) State.state(n) else State.put(n - 1) >> badCountdown
  })

  /** The only way to get the countdown to work with State.
    *
    * WART: What *should* be an implementation detail (Trampoline) is exposed to
    * the user and made necessary.
    *
    * WART: Doing the "Statey" operations requires using the `State` object
    * (not StateT), then explicitely lifting through the stacked monad (Trampoline).
    * `State.get.lift[Trampoline].flatMap` is *very* verbose just for a simple
    * `get` call.
    */
  def trampolineCountdown: StateT[Trampoline, Int, Int] = State.get.lift[Trampoline].flatMap({ n =>
    if (n <= 0) StateT(_ => Trampoline.done((n,n)))
    else State.put(n - 1).lift[Trampoline] >> trampolineCountdown
  })

  /** An extra `run` is necessary here to escape the Trampoline. */
  def runTrampoline: (Int, Int) = trampolineCountdown.run(10000).run

  /* --- APPLICATIVE --- */

  /** We don't `map` into the Applicative like in cats. */
  def dumbSum(nums: List[Int]): Option[Int] = nums match {
    case Nil => Some(0)
    case n :: ns => (n.some |@| dumbSum(ns)) { _ + _ }
  }

  /** Argument order for `<*>` seems backward from Haskell.
    * Cats doesn't have a `<*>`, but they do have `ap`.
    * Using `|@|` is favoured, however.
    *
    * WART: Type handholding in the lambda.
    * WART: Operator precedence is borked, such that you have to use parens in
    * order to ensure the correct order of operations. Otherwise the code won't
    * compile.
    */
  def dumbSum2(nums: List[Int]): Option[Int] = nums match {
    case Nil => Some(0)
    case n :: ns => dumbSum2(ns) <*> (n.some <*> { (a: Int) => (b: Int) => a+b }.some)
  }

  /* --- SIDE EFFECTS --- */

  /** Same usage as Cats, except that to run the IO we use the Haskell-inspired
    * method `.unsafePerformIO`
    */
  def greet(name: String): IO[Unit] = IO { println(s"Hi, ${name}!") }

  /** This doesn't appear to blow the stack, even without manual trampolining.
    * Unlike Cats, there is a bit of a slowdown if you use `(>>=)` instead of
    * `flatMap`, but luckily performance is linear with input size, like Cats.
    */
  def recurseIO(n: Int): IO[Int] = n match {
    case 0 => IO(0)
    case n => IO(n - 1).flatMap(recurseIO)
  }

  /** The `IO` type is aware of exceptions and can help you handle them. */
  def ioException: IO[Unit] =
    IO(println("Step 1")) >> IO { throw new Exception } >> IO(println("Step 2"))

  /** Various ways of handling Exceptions through IO. Since Exceptions are side-effects
    * and side-effects should only occur in IO, this is a good way to communicate
    * that exceptions are possible in an application.
    *
    * ScalaZ also has the `bracket` pattern from Haskell, which Cats did not emulate.
    */
  def catchingExceptions: Unit = {
    /* Prints "Step 1" then explodes */
    ioException.unsafePerformIO

    /* Prints "Step 1", then "crap", then explodes */
    ioException.onException(IO(println("crap"))).unsafePerformIO
    ioException.ensuring(IO(println("crap"))).unsafePerformIO

    /* Prints "Step 1", then "crap", then returns safely */
    ioException.except(_ => IO(println("crap"))).unsafePerformIO
  }
}

/* --- WRITING TYPECLASS INSTANCES --- */

/** A simple Rose Tree. */
case class Tree[T](root: T, children: Seq[Tree[T]])

object Tree {

  /** Trees are mappable. */
  implicit val treeFunctor: Functor[Tree] = new Functor[Tree] {
    def map[A, B](fa: Tree[A])(f: A => B): Tree[B] =
      Tree(f(fa.root), fa.children.map(t => t.map(f)))
   }

  /** Trees are Applicative Functors. */
  implicit val treeApplicative: Applicative[Tree] = new Applicative[Tree] {
    def point[A](a: => A): Tree[A] = Tree(a, Seq.empty)

    def ap[A, B](fa: => Tree[A])(tf: => Tree[A => B]): Tree[B] = {
      val Tree(f, tfs) = tf

      Tree(f(fa.root), fa.children.map(t => t.map(f)) ++ tfs.map(t => fa <*> t))
     }
   }

  /** Trees are also Monads. */
  implicit val treeMonad: Monad[Tree] = new Monad[Tree] {
    def point[A](a: => A): Tree[A] = treeApplicative.point(a)

    def bind[A, B](fa: Tree[A])(f: A => Tree[B]): Tree[B] = {
      val Tree(r2, k2) = f(fa.root)

      Tree(r2, k2 ++ fa.children.map(t => t >>= f))
    }
  }
}