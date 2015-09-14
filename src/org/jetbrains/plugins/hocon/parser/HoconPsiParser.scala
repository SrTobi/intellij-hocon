package org.jetbrains.plugins.hocon.parser

import java.net.{MalformedURLException, URL}
import java.{lang => jl, util => ju}

import com.intellij.lang.PsiBuilder.Marker
import com.intellij.lang.WhitespacesAndCommentsBinder.TokenTextGetter
import com.intellij.lang.{PsiBuilder, PsiParser, WhitespacesAndCommentsBinder, WhitespacesBinders}
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.hocon.CommonUtil._
import org.jetbrains.plugins.hocon.HoconConstants._
import org.jetbrains.plugins.hocon.lexer.HoconTokenSets._
import org.jetbrains.plugins.hocon.lexer.HoconTokenType._
import org.jetbrains.plugins.hocon.parser.HoconElementType._

import scala.annotation.tailrec
import scala.util.matching.Regex

class HoconPsiParser extends PsiParser {

  def parse(root: IElementType, builder: PsiBuilder) = {
    val file = builder.mark()
    new Parser(builder).parseFile()
    file.done(root)
    builder.getTreeBuilt
  }

  class Parser(builder: PsiBuilder) {

    object DocumentationCommentsBinder extends WhitespacesAndCommentsBinder {
      override def getEdgePosition(tokens: ju.List[IElementType], atStreamEdge: Boolean, getter: TokenTextGetter) = {

        @tailrec
        def docCommentsStart(acc: Int, i: Int): Int = {
          lazy val token = tokens.get(i)
          lazy val hashCommentStartingLine =
            token == HashComment && (atStreamEdge || (i > 0 && tokens.get(i - 1) == LineBreakingWhitespace))
          lazy val whitespaceAfterHashComment =
            i > 0 && tokens.get(i - 1) == HashComment && token == LineBreakingWhitespace &&
              getter.get(i).charIterator.count(_ == '\n') <= 1

          if (i >= tokens.size || !WhitespaceOrComment.contains(token)) acc
          else if (hashCommentStartingLine || whitespaceAfterHashComment) docCommentsStart(acc, i + 1)
          else docCommentsStart(i + 1, i + 1)
        }

        docCommentsStart(0, 0)
      }
    }

    // beware of rollbacks!
    var newLineSuppressedIndex: Int = 0

    def newLinesBeforeCurrentToken =
      builder.rawTokenIndex > newLineSuppressedIndex && builder.rawLookup(-1) == LineBreakingWhitespace

    def suppressNewLine(): Unit = {
      newLineSuppressedIndex = builder.rawTokenIndex
    }

    def advanceLexer(): Unit = {
      builder.advanceLexer()
    }

    def matches(matcher: Matcher) =
      (matcher.tokenSet.contains(builder.getTokenType) && (!matcher.requireNoNewLine || !newLinesBeforeCurrentToken)) ||
        (matcher.matchNewLine && newLinesBeforeCurrentToken) || (matcher.matchEof && builder.eof)

    def matchesUnquoted(str: String) =
      matches(UnquotedChars) && builder.getTokenText == str

    def matchesUnquoted(pattern: Regex) =
      matches(UnquotedChars) && pattern.pattern.matcher(builder.getTokenText).matches

    def pass(matcher: Matcher): Boolean = {
      val result = matches(matcher)
      if (result && (!matcher.matchNewLine || !newLinesBeforeCurrentToken) && (!matcher.matchEof || !builder.eof)) {
        advanceLexer()
      }
      result
    }

    def errorUntil(matcher: Matcher, msg: String, onlyNonEmpty: Boolean = false): Unit = {
      if (!onlyNonEmpty || !matches(matcher)) {
        val marker = builder.mark()
        while (!matches(matcher)) {
          builder.advanceLexer()
        }
        marker.error(msg)
      }
    }

    def tokenError(msg: String): Unit = {
      val marker = builder.mark()
      builder.advanceLexer()
      marker.error(msg)
    }

    def setEdgeTokenBinders(marker: Marker, nonGreedyLeft: Boolean, nonGreedyRight: Boolean): Unit = {
      import com.intellij.lang.WhitespacesBinders._
      marker.setCustomEdgeTokenBinders(
        if (nonGreedyLeft) DEFAULT_LEFT_BINDER else GREEDY_LEFT_BINDER,
        if (nonGreedyRight) DEFAULT_RIGHT_BINDER else GREEDY_RIGHT_BINDER)
    }

    def parseFile(): Unit = {
      if (matches(LBrace))
        parseObject()
      else
        parseObjectEntries(insideObject = false)
      errorUntil(Empty.orEof, "expected end of file", onlyNonEmpty = true)
    }

    def parseStringLiteral(): Unit = {
      val marker = builder.mark()

      val unclosedQuotedString = builder.getTokenType == QuotedString &&
        !ProperlyClosedQuotedString.pattern.matcher(builder.getTokenText).matches
      val unclosedMultilineString = builder.getTokenType == MultilineString && !builder.getTokenText.endsWith("\"\"\"")

      advanceLexer()

      if (unclosedQuotedString) {
        builder.error("unclosed quoted string")
      } else if (unclosedMultilineString) {
        builder.error("unclosed multiline string")
      }

      marker.done(String)
    }

    def parseObject() = {
      val marker = builder.mark()

      advanceLexer()
      parseObjectEntries(insideObject = true)
      if (!pass(RBrace)) {
        builder.error("expected '}'")
      }

      marker.done(Object)
    }

    def parseObjectEntries(insideObject: Boolean) = {
      val marker = builder.mark()

      while (!matches(RBrace.orEof)) {
        if (matches(ObjectEntryStart)) {
          parseObjectEntry()
          pass(Comma)
        } else {
          tokenError("expected object field" + (if (insideObject) ", include or '}'" else " or include"))
        }
      }

      marker.done(ObjectEntries)
      setEdgeTokenBinders(marker, nonGreedyLeft = false, nonGreedyRight = false)
    }

    def parseObjectEntry(): Unit = {
      if (matchesUnquoted("include"))
        parseInclude()
      else
        parseObjectField()
      errorUntil(ValueEnding.orNewLineOrEof, "unexpected token", onlyNonEmpty = true)
    }

    def parseInclude() = {
      val marker = builder.mark()

      advanceLexer()
      parseIncluded()

      marker.done(Include)
    }

    def parseIncluded(): Unit = {
      val marker = builder.mark()

      if (matches(QuotedString)) {
        parseStringLiteral()
      } else if (IncludeQualifiers.exists(matchesUnquoted)) {
        val qualifier = builder.getTokenText
        advanceLexer()
        if (matches(QuotedString)) {
          if (qualifier == UrlQualifier) {
            try {
              new URL(unquote(builder.getTokenText))
              parseStringLiteral()
            } catch {
              case e: MalformedURLException =>
                tokenError(if (e.getMessage != null) e.getMessage else "malformed URL")
            }
          } else {
            parseStringLiteral()
          }
          if (matchesUnquoted(")")) {
            advanceLexer()
          } else errorUntil(ValueEnding.orNewLineOrEof, "expected ')'")
        } else errorUntil(ValueEnding.orNewLineOrEof, "expected quoted string")
      } else errorUntil(ValueEnding.orNewLineOrEof,
        "expected quoted string, optionally wrapped in 'url(...)', 'file(...)' or 'classpath(...)'")

      marker.done(Included)
    }

    def parseObjectField(): Unit = {
      val marker = builder.mark()
      parseKeyedField(true)
      marker.done(ObjectField)

      marker.setCustomEdgeTokenBinders(DocumentationCommentsBinder, WhitespacesBinders.DEFAULT_RIGHT_BINDER)
    }

    def parseKeyedField(first: Boolean): Unit = {
      if (first) {
        suppressNewLine()
      }

      val marker = builder.mark()
      tryParseKey(first)

      if (pass(Period.noNewLine)) {
        parseKeyedField(first = false)
        marker.done(PrefixedField)
      } else {
        if (matches(LBrace)) {
          parseObject()
        } else if (pass(KeyValueSeparator)) {
          if (matches(ValueStart)) {
            parseValue()
          } else {
            errorUntil(ValueEnding.orNewLineOrEof, "expected value for object field")
          }
        } else errorUntil(ValueEnding.orNewLineOrEof,
          "expected ':', '=', '+=' or object")
        marker.done(ValuedField)
      }

      setEdgeTokenBinders(marker, first, nonGreedyRight = true)
    }

    def parsePath(prefixMarker: Option[Marker] = None): Unit = {
      val first = prefixMarker.isEmpty
      if (first) {
        suppressNewLine()
      }

      if (!matches(PathEnding.orNewLineOrEof)) {
        if (!first) {
          pass(Period.noNewLine)
        }
        val marker = prefixMarker.map(_.precede()).getOrElse(builder.mark())
        tryParseKey(first)
        marker.done(Path)
        parsePath(Some(marker))
      }
    }

    def tryParseKey(first: Boolean): Unit = {
      if (!matches(KeyEnding.orNewLineOrEof)) {
        parseKey(first)
      } else {
        builder.error("expected key (use quoted \"\" if you want empty key)")
      }
    }

    def parseKey(first: Boolean): Unit = {
      val marker = builder.mark()

      @tailrec
      def parseKeyParts(first: Boolean): Unit = {
        if (!matches(KeyEnding.orNewLineOrEof)) {
          if (matches(UnquotedChars)) {
            parseUnquotedString(UnquotedChars.noNewLine, first, PathEnding.orNewLineOrEof)
          } else if (matches(StringLiteral)) {
            parseStringLiteral()
          } else {
            tokenError("key must be a concatenation of unquoted, quoted or multiline strings " +
              "(characters $ \" { } [ ] : = , + # ` ^ ? ! @ * & \\ are forbidden unquoted)")
          }
          parseKeyParts(first = false)
        }
      }

      suppressNewLine()
      parseKeyParts(first)

      marker.done(Key)

      setEdgeTokenBinders(marker, first, matches(PathEnding.orNewLineOrEof))
    }

    def parseUnquotedString(matcher: Matcher, nonGreedyLeft: Boolean, nonGreedyRightMatcher: Matcher): Unit = {
      val stringMarker = builder.mark()
      val marker = builder.mark()
      suppressNewLine()
      while (matches(matcher)) {
        advanceLexer()
      }
      marker.done(UnquotedString)
      setEdgeTokenBinders(marker, nonGreedyLeft, matches(nonGreedyRightMatcher))
      stringMarker.done(String)
      setEdgeTokenBinders(stringMarker, nonGreedyLeft, matches(nonGreedyRightMatcher))
    }

    def parseValue(): Unit = {
      def tryParse(parsingCode: => Boolean, element: HoconElementType): Boolean = {
        val marker = builder.mark()
        if (parsingCode) {
          marker.done(element)
          true
        } else {
          marker.rollbackTo()
          false
        }
      }

      def passKeyword(kw: String) =
        if (matchesUnquoted(kw)) {
          advanceLexer()
          true
        } else
          false

      val endingMatcher = ValueEnding.orNewLineOrEof

      def tryParseNull = tryParse(passKeyword("null") && matches(endingMatcher), Null)
      def tryParseBoolean = tryParse((passKeyword("true") || passKeyword("false")) && matches(endingMatcher), Boolean)
      def tryParseNumber = tryParse(passNumber() && matches(endingMatcher), Number)

      @tailrec
      def parseValueParts(partCount: Int): Int = {
        if (!matches(endingMatcher)) {
          if (matches(LBrace)) {
            parseObject()
          } else if (matches(LBracket)) {
            parseArray()
          } else if (matches(Dollar)) {
            parseSubstitution()
          } else if (matches(ValueUnquotedChars)) {
            parseUnquotedString(ValueUnquotedChars.noNewLine, partCount == 0, ValueEnding.orNewLineOrEof)
          } else if (matches(StringLiteral)) {
            parseStringLiteral()
          } else {
            tokenError("characters $ \" { } [ ] : = , + # ` ^ ? ! @ * & \\ are forbidden unquoted")
          }
          parseValueParts(partCount + 1)
        } else partCount
      }

      suppressNewLine()
      if (!tryParseNull && !tryParseBoolean && !tryParseNumber) {
        val marker = builder.mark()
        val parts = parseValueParts(0)
        if (parts > 1) {
          marker.done(Concatenation)
        } else {
          marker.drop()
        }

      }

    }

    def passNumber(): Boolean = matchesUnquoted(IntegerPattern) && {
      val textBuilder = new StringBuilder
      // we need to detect whitespaces between tokens forming a number to behave as if number is a single token
      val integerRawTokenIdx = builder.rawTokenIndex
      textBuilder ++= builder.getTokenText
      advanceLexer()

      val gotPeriod = matches(Period)
      val noPeriodWhitespace = gotPeriod && builder.rawTokenIndex == integerRawTokenIdx + 1

      if (gotPeriod) {
        textBuilder ++= builder.getTokenText
        advanceLexer()
      }

      val gotDecimalPart = gotPeriod && matchesUnquoted(DecimalPartPattern)
      val noDecimalPartWhitespace = gotDecimalPart && builder.rawTokenIndex == integerRawTokenIdx + 2

      if (gotDecimalPart) {
        textBuilder ++= builder.getTokenText
        advanceLexer()
      }

      lazy val isValid = {
        val text = textBuilder.result()
        try {
          if (gotPeriod) text.toDouble else text.toLong
          true
        } catch {
          case e: NumberFormatException => false
        }
      }

      (!gotPeriod || noPeriodWhitespace) && (!gotDecimalPart || noDecimalPartWhitespace) && isValid
    }

    def parseArray(): Unit = {
      val marker = builder.mark()
      advanceLexer()

      while (!matches(ArrayElementsEnding.orEof)) {
        if (matches(ValueStart)) {
          parseValue()
          pass(Comma)
        } else {
          tokenError("expected array element or ']'")
        }
      }

      if (!pass(RBracket)) {
        builder.error("expected ']'")
      }

      marker.done(Array)
    }

    def parseSubstitution(): Unit = {
      val marker = builder.mark()
      advanceLexer()
      advanceLexer()
      pass(QMark)
      if (matches(SubstitutionPathStart.noNewLine)) {
        parsePath()
        if (!pass(SubRBrace)) {
          builder.error("expected '}'")
        }
      } else errorUntil(PathEnding.orNewLineOrEof, "expected path expression")
      pass(SubRBrace)

      marker.done(Substitution)
    }

  }

}
