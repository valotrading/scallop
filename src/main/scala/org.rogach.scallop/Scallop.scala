package org.rogach.scallop

import scala.reflect.Manifest
import org.rogach.scallop.exceptions._

/** The creator and god of all parsers :) */
object Scallop {
  
  /** Create the new parser with some arguments already inserted.
    *
    * @param args Args to pre-insert.
    */
  def apply(args: Seq[String]): Scallop = new Scallop(args)
  
  /** Create the default empty parser, fresh as mountain air. */
  def apply(): Scallop = apply(Nil)

}

/** The main builder class.
  *
  * @param args Arguments to parse.
  * @param opts Options definitions.
  * @param propts Property options definitions.
  * @param trail Definitions for trailing arguments.
  * @param vers Version string to display in help.
  * @param bann Banner (summary of this program and command-line usage) to display in help.
  * @param foot Footer - displayed after options.
  */
case class Scallop(
    args: Seq[String] = Nil,
    opts: List[CliOption] = Nil,
    vers: Option[String] = None,
    bann: Option[String] = None,
    foot: Option[String] = None,
    optionSetValidations: List[List[String]=>Either[String, Unit]] = Nil,
    helpWidth: Option[Int] = None,
    subbuilders: List[(String, Scallop)] = Nil) {

  type Parsed = List[(CliOption, (String, List[String]))]
  
  case class ParseResult(
    opts: List[(CliOption, (String, List[String]))] = Nil,
    subcommand: Option[String] = None,
    subcommandArgs: List[String] = Nil
  )

  /** Parse the argument into list of options and their arguments. */
  private def parse(args: Seq[String]): ParseResult = {
    subbuilders.find(sub => args.contains(sub._1)) match {
      case Some((name, sub)) => ParseResult(parse(Nil, args.takeWhile(name!=)), Some(name), args.dropWhile(name!=).drop(1).toList)
      case None => ParseResult(parse(Nil, args))
    }
  }
  @annotation.tailrec
  private def parse(acc: Parsed, args: Seq[String]): Parsed = {

    def goParseRest(args: Seq[String], opt: Option[(String, CliOption)]): Parsed = { 
      def parseRest = {
        parseTrailingArgs(
          args.toList,
          opt.map(o=> (o._2.converter, true)).toList ::: opts.filter(_.isPositional).map(o => (o.converter, o.required))
        ) map { res => 
          (opt.toList ::: opts.filter(_.isPositional).map(("",_))) zip res filter { 
            case ((invoc, opt), p) => !opt.isPositional || p.size > 0 
          } 
        } getOrElse (throw new OptionParseException(args)) map { case ((invoc, opt), p) => (opt, (invoc, p)) }
     }
     
     opt match {
        case Some((invoc, o)) =>
          // short-circuit parsing when there are no trailing args - to get better error messages
          o.converter.argType match {
            case ArgType.FLAG   => 
              (o, (invoc, Nil)) :: goParseRest(args, None)
            case ArgType.SINGLE =>
              (o, (invoc, args.take(1).toList)) :: goParseRest(args.tail, None)
            case ArgType.LIST   => parseRest
          }
        case None => parseRest
      }
    }

    if (args.isEmpty) acc
    else if (isOptionName(args.head) && args.head != "--") {
      if (args.head.startsWith("--")) {
        val opt = opts find (_.longNames.contains(args.head.drop(2))) getOrElse
                  (throw new UnknownOption(args.head.drop(2)))
        val (before, after) = args.tail.span(isArgument)
        if (after.isEmpty) {
          // get the converter, proceed to trailing args parsing
          acc ::: goParseRest(args.tail, Some((args.head.drop(2),opt)))
        } else {
          parse( acc = (opt -> (args.head.drop(2), before.toList)) :: acc,
                 args = after)
        }
      } else {
        if (args.head.size == 2) {
          val opt = getOptionWithShortName(args.head(1)) getOrElse 
                    (throw new UnknownOption(args.head.drop(1)))
          val (before, after) = args.tail.span(isArgument)
          if (after.isEmpty) {
            // get the converter, proceed to trailing args parsing
            acc ::: goParseRest(args.tail, Some((args.head.drop(1), opt)))
          } else {
            parse( acc = (opt -> (args.head.drop(1), before.toList)) :: acc,
                   args = after)
          }
        } else {
          val opt = getOptionWithShortName(args.head(1)) getOrElse
                    (throw new UnknownOption(args.head.drop(1)))
          if (opt.converter.argType != ArgType.FLAG) {
            parse(acc, args.head.take(2) +: args.head.drop(2) +: args.tail)
          } else {
            parse(acc, args.head.take(2) +: ("-" + args.head.drop(2)) +: args.tail)
          }
        }
      }
    } else {
      // only trailing args left - proceed to trailing args parsing
      val trailArgs = if (args.head == "--") args.tail else args
      acc ::: goParseRest(trailArgs, None)
    }
  }
  
  /** Find an option, that responds to this short name. */
  def getOptionWithShortName(c: Char): Option[CliOption] = {
    opts find (_.requiredShortNames.contains(c)) orElse {
      opts find (_.shortNames.contains(c))
    }
  }
  
  def getOptionShortNames(opt: CliOption): List[Char] = {
    (opt.shortNames ++ opt.requiredShortNames).distinct.filter(sh => getOptionWithShortName(sh).get == opt)
  }
  
  /** Result of parsing */ 
  private lazy val parsed: ParseResult = if (args.headOption map("@--" ==) getOrElse false) {
    // read options from stdin
    val argList = 
      io.Source.fromInputStream(java.lang.System.in).getLines.toList
      .flatMap(_.split(" ").filter(_.size > 0))
    parse(argList)
  } else if (args.headOption map(_ startsWith "@") getOrElse false) {
    // read options from a file (canned config)
    val argList = 
      io.Source.fromFile(args.head.drop(1)).getLines.toList
      .flatMap(_.split(" ").filter(_.size > 0))
    parse(argList)
  } else parse(args)

  /** cache for option&property values returned from this builder. */
//  private var getCache = scala.collection.mutable.Map[(String,Manifest[_]),Any]()

  /** Tests whether this string contains option name, not some number. */
  private def isOptionName(s: String) = 
    if (s.startsWith("-"))
      if (s.size > 1)
        !s(1).isDigit
      else true
    else false
  
  /** Tests whether this string contains option parameter, not option call. */
  private def isArgument(s: String) = !isOptionName(s)
  
  /** Parses the trailing arguments (including the arguments to last option).
    *
    * Uses simple backtraking algorithm.
    * @param args arguments to parse
    * @param convs list of converters and the flags, indicating if that converter must match
    * @return None if match fails, a list of argument lists otherwise. The size of returned list is equal to size of
    *         converter list.
    */
  private def parseTrailingArgs(args: List[String], convs: List[(ValueConverter[_], Boolean)]): Option[List[List[String]]] = {
    if (convs.isEmpty) {
      if (args.isEmpty) Some(Nil)
      else None // some arguments are still left, and there are no converters to match them => no match
    } else {
      // the remainders of arguments, to be matched by subsequent converters
      val remainders = convs.head._1.argType match {
        case ArgType.FLAG => List(args) // all of them
        case ArgType.SINGLE => // either the full list or it's tail - we can match only one argument
          if (convs.head._2) 
            if (args.isEmpty) List(Nil) else List(args.tail)
          else 
            if (args.isEmpty) List(Nil) else List(args.tail, args)
        case ArgType.LIST =>
          args.tails.toList.reverse
      }
      remainders.view map { rem =>
        val p = args.take(args.size - rem.size) // to be matched by current converter
        if (p.isEmpty && !convs.head._2) { // will it match an empty list?
            val next = parseTrailingArgs(rem, convs.tail)
            if (next.isDefined) Some(p :: next.get)
            else None 
        } else {
          convs.head._1.parse(List(("",p))) match {
            case Right(a) if a.isDefined => 
              val next = parseTrailingArgs(rem, convs.tail)
              if (next.isDefined) Some(p :: next.get)
              else None
            case _ => None
          }
        }
      } find (_.isDefined) getOrElse (None)
    }
  }
  
  /** Add a new option definition to this builder.
    *
    * @param name Name for new option, used as long option name in parsing, and for option identification.
    * @param short Overload the char that will be used as short option name. 
                   Defaults to first character of the name.
    * @param descr Description for this option, for help description.
    * @param default Default value to use if option is not found in input arguments
                     (if you provide this, you can omit the type on method).
    * @param required Is this option required? Defaults to false.
    * @param arg The name for this ortion argument, as it will appear in help. Defaults to "arg".
    * @param noshort If set to true, then this option does not have any short name.
    * @param conv The converter for this option. Usually found implicitly.
    * @param validate The function, that validates the parsed value
    * @param hidden Hides description of this option from help (this can be useful for debugging options)
    */
  def opt[A](
      name: String,
      short: Char = 0.toChar,
      descr: String = "",
      default: Option[A] = None,
      validate: A => Boolean = ((_:A) => true),
      required: Boolean = false,
      arg: String = "arg",
      hidden: Boolean = false,
      noshort: Boolean = false)
      (implicit conv: ValueConverter[A]): Scallop = {
    if (name.head.isDigit) throw new IllegalOptionParameters("First character of the option name must not be a digit: %s" format name)
    val defaultA = 
      if (conv == flagConverter)
        if (default == Some(true)) Some(true)
        else Some(false)
      else default
    val eShort = if (short == 0.toChar || noshort) None else Some(short)
    val validator = { (m:Manifest[_], a:Any) => 
      if (m >:> conv.manifest) validate(a.asInstanceOf[A])
      else false
    }
    this.copy(opts = opts :+ SimpleOption(name, 
                                          eShort,
                                          descr,
                                          required, 
                                          conv, 
                                          defaultA, 
                                          validator,
                                          arg,
                                          hidden,
                                          noshort))
  }
  
  
  /** Add new property option definition to this builder.
    *
    * @param name Char, that will be used as prefix for property arguments.
    * @param descr Description for this property option, for help description.
    * @param keyName Name for 'key' part of this option arg name, as it will appear in help option definition. Defaults to "key".
    * @param valueName Name for 'value' part of this option arg name, as it will appear in help option definition. Defaults to "value".
    */
  def props[A](
      name: Char,
      descr: String = "",
      keyName: String = "key",
      valueName: String = "value",
      hidden: Boolean = false)
      (implicit conv: ValueConverter[Map[String,A]]): Scallop =
    this.copy(opts = opts :+ PropertyOption(name.toString, name, descr, conv, keyName, valueName, hidden))
  
  def propsLong[A](
      name: String,
      descr: String = "",
      keyName: String = "key",
      valueName: String = "value",
      hidden: Boolean = false)
      (implicit conv: ValueConverter[Map[String,A]]): Scallop =
    this.copy(opts = opts :+ LongNamedPropertyOption(name,
                                                     descr,
                                                     conv,
                                                     keyName,
                                                     valueName,
                                                     hidden))

  /** Add new trailing argument definition to this builder.
    *
    * @param name Name for new definition, used for identification.
    * @param required Is this trailing argument required? Defaults to true.
    * @param default If this argument is not required and not found in the argument list, use this value.
    * @param validate The function, that validates the parsed value
    */
  def trailArg[A](
      name: String,
      required: Boolean = true,
      descr: String = "",
      default: Option[A] = None,
      validate: A => Boolean = ((_:A) => true),
      hidden: Boolean = false)
      (implicit conv: ValueConverter[A]): Scallop = {
    val defaultA = 
      if (conv == flagConverter)
        if (default == Some(true)) Some(true)
        else Some(false)
      else default
    val validator = { (m:Manifest[_], a:Any) => 
      if (m >:> conv.manifest) validate(a.asInstanceOf[A])
      else false
    }
    this.copy(opts = opts :+ TrailingArgsOption(name,
                                                required,
                                                descr,
                                                conv,
                                                validator,
                                                defaultA,
                                                hidden))
  }

  def toggle(
      name: String,
      default: Option[Boolean] = None,
      short: Char = 0.toChar,
      noshort: Boolean = false,
      prefix: String = "no",
      descrYes: String = "",
      descrNo: String = "",
      hidden: Boolean = false) = {
    val eShort = if (short == 0.toChar || noshort) None else Some(short)
    this.copy(opts = opts :+ ToggleOption(name,
                                          default,
                                          eShort,
                                          noshort,
                                          prefix,
                                          descrYes,
                                          descrNo,
                                          hidden))
  }
  
  /** Adds a subbuilder (subcommand) to this builder.
    * @param name All arguments after this string would be routed to this builder.
    */
  def addSubBuilder(name: String, builder: Scallop) = this.copy(subbuilders = subbuilders :+ (name -> builder))
  
  /** Retrieves name of the subcommand that was found in input arguments. */
  def getSubcommandName = parsed.subcommand
  
  /** Returns the list of subcommand names, recursively. */
  def getSubcommandNames: List[String] = {
    parsed.subcommand.map(subName => subbuilders.find(_._1 == subName).map(s => s._1 :: s._2.args(parsed.subcommandArgs).getSubcommandNames).getOrElse(Nil)).getOrElse(Nil)
  }
  
  /** Retrieves a list of all supplied options (including options from subbuilders). */
  def getAllSuppliedOptionNames: List[String] = {
    opts.map(_.name).filter(isSupplied) ::: parsed.subcommand.map(subName => subbuilders.find(_._1 == subName).map(s => s._2.args(parsed.subcommandArgs)).get.getAllSuppliedOptionNames.map(subName + "\0" + _)).getOrElse(Nil)
  }

  /** Add a validation for supplied option set.
    *
    * @param fn A function, that accepts the list of names of options, that are supplied.
    *           It should return a Left with error message in case of validation failure.
    */
  def validationSet(fn: List[String] => Either[String, Unit]) =
    this.copy(optionSetValidations = optionSetValidations :+ fn)
    
  /** Add version string to this builder.
    *
    * @param v Version string, to be printed before all other things in help.
    */
  def version(v: String) = this.copy(vers = Some(v))
  
  /** Add banner string to this builder. Banner should describe your program and provide a short
    * summary on it's usage.
    *
    * @param b Banner string, can contain multiple lines. Note this is not formatted to 80 characters!
    */
  def banner(b: String) = this.copy(bann = Some(b))
  
  /** Add footer string to this builder. Footer will be printed in help after option definitions.
    *
    * @param f Footer string, can contain multiple lines. Note this is not formatted to 80 characters!
    */
  def footer(f: String) = this.copy(foot = Some(f))
  
  /** Explicitly sets the needed width for the help printout. */
  def setHelpWidth(w: Int) = this.copy(helpWidth = Some(w))
  
  /** Get help on options from this builder. The resulting help is carefully formatted to required number of columns (default = 80, change with .setHelpWidth method),
    * and contains info on proporties and options. It does not contain info about trailing arguments.
    */
  def help: String = {
    val optsHelp = Formatter format (opts filter (!_.isPositional) filter (!_.hidden) sortBy (_.name.toLowerCase) flatMap (o => o.helpInfo(getOptionShortNames(o))), helpWidth)
    val subcommandsHelps = subbuilders.map { case (sn, sub) =>
      ("Subcommand: %s" format sn) + "\n" + sub.help
    }
    val trailHelp = Formatter format (opts filter (_.isPositional) filter (!_.hidden) flatMap (_.helpInfo(Nil)), helpWidth)
    if (opts filter (_.isPositional) isEmpty) {
      optsHelp + (if (subcommandsHelps.size > 0) "\n\n" + subcommandsHelps.mkString("\n") else "")
    } else {
      optsHelp + "\n\n trailing arguments:\n" + trailHelp + "\n"
    }
  }
    
  /** Print help message (with version, banner, option usage and footer) to stdout. */
  def printHelp = {
    vers foreach println
    bann foreach println
    println(help)
    foot foreach println
  }
  
  /** Add some more arguments to this builder. They are appended to the end of the original list.
    *
    * @param a arg list to add
    */
  def args(a: Seq[String]) = this.copy(args = args ++ a)

  /** Tests if this option or trailing arg was explicitly provided by argument list (not from default).
    *
    * @param name Identifier of option or trailing arg definition
    */
  def isSupplied(name: String): Boolean = {
    if (name.contains('\0')) {
      // delegating to subbuilder
      subbuilders.find(_._1 == name.takeWhile('\0'!=)).map(_._2.args(parsed.subcommandArgs).isSupplied(name.dropWhile('\0'!=).drop(1)))
        .getOrElse(throw new UnknownOption(name.replace("\0",".")))
    } else {
      opts find (_.name == name) map { opt =>
        val args = parsed.opts.filter(_._1 == opt).map(_._2)
        opt.converter.parse(args).right.get.isDefined
      } getOrElse(throw new UnknownOption(name))
    }
  }
  
   /** Get the value of option (or trailing arg) as Option.
     * @param name Name for option.
     * @param m Manifest for requested type. Usually found implicitly.
     */
  def get[A](name: String)(implicit m: Manifest[A]): Option[A] = {
    if (name.contains('\0')) {
      // delegating to subbuilder
      subbuilders.find(_._1 == name.takeWhile('\0'!=)).map(_._2.args(parsed.subcommandArgs).get(name.dropWhile('\0'!=).drop(1))(m))
        .getOrElse(throw new UnknownOption(name.replace("\0","."))).asInstanceOf[Option[A]]
    } else {
      opts.find(_.name == name).map{ opt =>
        if (!(opt.converter.manifest <:< m))
          throw new WrongTypeRequest(m, opt.converter.manifest)
        val args = parsed.opts.filter(_._1 == opt).map(_._2)
        opt.converter.parse(args).right
          .getOrElse(if (opt.required)  throw new MajorInternalException else None)
          .orElse(opt.default)
      }.getOrElse(throw new UnknownOption(name)).asInstanceOf[Option[A]]
    }
  }
  
  def get[A](name: Char)(implicit m: Manifest[A]): Option[A] = get(name.toString)(m)
    
  /** Get the value of option. If option is not found, this will throw an exception.
    *
    * @param name Name for option.
    * @param m Manifest for requested type. Usually found implicitly.
    */
  def apply[A](name: String)(implicit m: Manifest[A]): A = get(name)(m).get
  
  def apply[A](name: Char)(implicit m: Manifest[A]): A = apply(name.toString)(m)
  
  def prop[A](name: Char, key: String)(implicit m: Manifest[Map[String, A]]): Option[A] = apply(name)(m).get(key)
  
  /** Verify the builder. Parses arguments, makes sure no definitions clash, no garbage or unknown options are present,
    * and all present arguments are in proper format. It is recommended to call this method before using the results.
    * 
    * If there is "--help" or "--version" option present, it prints help or version statement and exits.
    */
  def verify: Scallop = {
    // option identifiers must not clash 
    opts map (_.name) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames("Option identifier '%s' is not unique" format a._1))
    // long options names must not clash
    opts flatMap (_.longNames) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames("Long option name '%s' is not unique" format a._1))
    // short options names must not clash
    opts flatMap (o => (o.requiredShortNames).distinct) groupBy (a=>a) filter (_._2.size > 1) foreach
      (a => throw new IdenticalOptionNames("Short option name '%s' is not unique" format a._1))
    
    if (args contains "--help") {
      throw Help
    }
    if (vers.isDefined && args.contains("--version")) {
      throw Version
    }
    
    parsed
   
    // validate option sets
    optionSetValidations map (
      _(getAllSuppliedOptionNames)
    ) find (_.isLeft) map { l =>
      throw new OptionSetValidationFailure(l.left.get)
    }
    
    // verify subcommand parsing
    parsed.subcommand.map { sn =>
      subbuilders.find(_._1 == sn).map { case (sn, sub)=>
        sub.args(parsed.subcommandArgs).verify
      }
    }
    
    opts foreach { o =>
      val args = parsed.opts filter (_._1 == o) map (_._2)
      val res = o.converter.parse(args)
      if (res.isLeft) throw new WrongOptionFormat(o.name, args.map(_._2.mkString(" ")).mkString(" "))
      if (o.required && !res.right.get.isDefined && !o.default.isDefined) 
        throw new RequiredOptionNotFound(o.name)
      // validaiton
      if (!(get(o.name)(o.converter.manifest) map (v => o.validator(o.converter.manifest,v)) getOrElse true))
        throw new ValidationFailure("Validation failure for '%s' option parameters: %s" format (o.name, args.map(_._2.mkString(" ")).mkString(" ")))

    }

    this
  }
  
  /** Get summary of current parser state.
    *
    * Returns a list of all options in the builder, and corresponding values for them.
    */
  def summary: String = {
    ("Scallop(%s)" format args.mkString(", ")) + "\n" +
    opts.map(o => 
      " %s  %s => %s" format ((if (isSupplied(o.name)) "*" else " "),
                              o.name,
                              get(o.name)(o.converter.manifest).getOrElse("$None$"))
    ).mkString("\n") + "\n" + parsed.subcommand.map { sn =>
      ("subcommand: %s\n" format sn) + subbuilders.find(_._1 == sn).get._2.args(parsed.subcommandArgs).summary
    }.getOrElse("")
  }
  
}

