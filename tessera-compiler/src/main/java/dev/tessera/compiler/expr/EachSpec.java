package dev.tessera.compiler.expr;

import dev.tessera.compiler.SourcePos;

/** {@code t:each="var[, statVar] : iterable"}. {@code statVar} is null when omitted (then {@code varStat} is implied). */
public record EachSpec(String var, String statVar, Expr iterable, SourcePos pos) {}
