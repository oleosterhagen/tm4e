/**
 * Copyright (c) 2015-2017 Angelo ZERR.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Initial code from https://github.com/microsoft/vscode-textmate/
 * Initial copyright Copyright (C) Microsoft Corporation. All rights reserved.
 * Initial license: MIT
 *
 * Contributors:
 * - Microsoft Corporation: Initial code, written in TypeScript, licensed under MIT license
 * - Angelo Zerr <angelo.zerr@gmail.com> - translation and adaptation to Java
 * - Fabio Zadrozny <fabiofz@gmail.com> - Not adding '\n' on tokenize if it already finished with '\n'
 */
package org.eclipse.tm4e.core.internal.grammar;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult2;
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes;
import org.eclipse.tm4e.core.internal.matcher.Matcher;
import org.eclipse.tm4e.core.internal.oniguruma.OnigString;
import org.eclipse.tm4e.core.internal.registry.IGrammarRepository;
import org.eclipse.tm4e.core.internal.registry.IThemeProvider;
import org.eclipse.tm4e.core.internal.rule.IRuleFactoryHelper;
import org.eclipse.tm4e.core.internal.rule.Rule;
import org.eclipse.tm4e.core.internal.rule.RuleFactory;
import org.eclipse.tm4e.core.internal.rule.RuleId;
import org.eclipse.tm4e.core.internal.types.IRawGrammar;
import org.eclipse.tm4e.core.internal.types.IRawRepository;
import org.eclipse.tm4e.core.internal.types.IRawRule;

/**
 * TextMate grammar implementation.
 *
 * @see <a href=
 *      "https://github.com/microsoft/vscode-textmate/blob/e8d1fc5d04b2fc91384c7a895f6c9ff296a38ac8/src/grammar.ts#L99">
 *      github.com/microsoft/vscode-textmate/blob/main/src/grammar.ts</a>
 */
public final class Grammar implements IGrammar, IRuleFactoryHelper {

	private static final Logger LOGGER = System.getLogger(Grammar.class.getName());

	private final String rootScopeName;

	@Nullable
	private RuleId _rootId = null;
	private int _lastRuleId = 0;
	private final Map<RuleId, @Nullable Rule> _ruleId2desc = new HashMap<>();
	private final Map<String /*scopeName*/, IRawGrammar> includedGrammars = new HashMap<>();
	private final IGrammarRepository _grammarRepository;
	private final IRawGrammar _grammar;
	final IThemeProvider themeProvider;

	@Nullable
	private List<Injection> _injections;
	private final BasicScopeAttributesProvider _basicScopeAttributesProvider;
	private final List<TokenTypeMatcher> _tokenTypeMatchers = new ArrayList<>();

	@Nullable
	private final BalancedBracketSelectors balancedBracketSelectors;

	public Grammar(
		final String rootScopeName,
		final IRawGrammar grammar,
		final int initialLanguage,
		@Nullable final Map<String, Integer> embeddedLanguages,
		@Nullable final Map<String, Integer> tokenTypes,
		@Nullable final BalancedBracketSelectors balancedBracketSelectors,
		final IGrammarRepository grammarRepository,
		final IThemeProvider themeProvider) {

		this.rootScopeName = rootScopeName;
		this._basicScopeAttributesProvider = new BasicScopeAttributesProvider(
			initialLanguage,
			embeddedLanguages);
		this._grammarRepository = grammarRepository;
		this._grammar = initGrammar(grammar, null);
		this.balancedBracketSelectors = balancedBracketSelectors;
		this.themeProvider = themeProvider;

		if (tokenTypes != null) {
			for (final var entry : tokenTypes.entrySet()) {
				final var selector = entry.getKey();
				final var type = entry.getValue();
				for (final var matcher : Matcher.createMatchers(selector)) {
					_tokenTypeMatchers.add(new TokenTypeMatcher(matcher.matcher, type));
				}
			}
		}
	}

	BasicScopeAttributes getMetadataForScope(final String scope) {
		return this._basicScopeAttributesProvider.getBasicScopeAttributes(scope);
	}

	private void collectInjections(final List<Injection> result, final String selector, final IRawRule rule,
		final IRuleFactoryHelper ruleFactoryHelper, final IRawGrammar grammar) {
		final var matchers = Matcher.createMatchers(selector);
		final var ruleId = RuleFactory.getCompiledRuleId(rule, ruleFactoryHelper, this._grammar.getRepository());
		for (final var matcher : matchers) {
			result.add(new Injection(
				selector,
				matcher.matcher,
				ruleId,
				grammar,
				matcher.priority));
		}
	}

	private List<Injection> _collectInjections() {
		final var grammarRepository = new IGrammarRepository() {
			@Override
			public @Nullable IRawGrammar lookup(final String scopeName) {
				if (Objects.equals(scopeName, Grammar.this.rootScopeName)) {
					return Grammar.this._grammar;
				}
				return getExternalGrammar(scopeName, null);
			}

			@Override
			public @Nullable Collection<String> injections(final String targetScope) {
				return Grammar.this._grammarRepository.injections(targetScope);
			}
		};

		final var result = new ArrayList<Injection>();

		final var scopeName = this.rootScopeName;

		final var grammar = grammarRepository.lookup(scopeName);
		if (grammar != null) {
			// add injections from the current grammar
			final var rawInjections = grammar.getInjections();
			if (rawInjections != null) {
				for (final var e : rawInjections.entrySet()) {
					collectInjections(
						result,
						e.getKey(),
						e.getValue(),
						this,
						grammar);
				}
			}

			// add injection grammars contributed for the current scope
			final var injectionScopeNames = this._grammarRepository.injections(scopeName);
			if (injectionScopeNames != null) {
				injectionScopeNames.forEach(injectionScopeName -> {
					final var injectionGrammar = Grammar.this.getExternalGrammar(injectionScopeName, null);
					if (injectionGrammar != null) {
						final var selector = injectionGrammar.getInjectionSelector();
						if (selector != null) {
							collectInjections(
								result,
								selector,
								injectionGrammar.toRawRule(),
								this,
								injectionGrammar);
						}
					}
				});
			}
		}

		Collections.sort(result, (i1, i2) -> i1.priority - i2.priority); // sort by priority

		return result;
	}

	List<Injection> getInjections() {
		var injections = this._injections;
		if (injections == null) {
			injections = this._injections = this._collectInjections();

			if (LOGGER.isLoggable(Level.TRACE) && !injections.isEmpty()) {
				LOGGER.log(Level.TRACE,
					"Grammar " + rootScopeName + " contains the following injections:");
				for (final var injection : injections) {
					LOGGER.log(Level.TRACE, "  - " + injection.debugSelector);
				}
			}
		}
		return injections;
	}

	@Override
	public <T extends Rule> T registerRule(final Function<RuleId, T> factory) {
		final var id = RuleId.of(++this._lastRuleId);
		final @Nullable T result = factory.apply(id);
		this._ruleId2desc.put(id, result);
		return result;
	}

	@Override
	public Rule getRule(final RuleId ruleId) {
		final var rule = this._ruleId2desc.get(ruleId);
		if (rule == null) {
			throw new IndexOutOfBoundsException(
				"No rule with index " + ruleId.id + " found. Possible values: 0.." + this._ruleId2desc.size());
		}
		return rule;
	}

	@Override
	@Nullable
	public IRawGrammar getExternalGrammar(final String scopeName, @Nullable final IRawRepository repository) {
		if (this.includedGrammars.containsKey(scopeName)) {
			return this.includedGrammars.get(scopeName);
		}

		final IRawGrammar rawIncludedGrammar = this._grammarRepository.lookup(scopeName);
		if (rawIncludedGrammar != null) {
			this.includedGrammars.put(scopeName, initGrammar(
				rawIncludedGrammar,
				repository != null ? repository.getBase() : null));
			return this.includedGrammars.get(scopeName);
		}
		return null;
	}

	private IRawGrammar initGrammar(IRawGrammar grammar, @Nullable final IRawRule base) {
		grammar = grammar.deepClone();

		final var repo = grammar.isRepositorySet()
			? grammar.getRepository()
			: new RawRepository();
		grammar.setRepository(repo);

		repo.setSelf(new RawRule()
			.setName(grammar.getScopeName())
			.setPatterns(grammar.getPatterns()));
		repo.setBase(base != null ? base : repo.getSelf());
		return grammar;
	}

	@Override
	public ITokenizeLineResult tokenizeLine(final String lineText) {
		return tokenizeLine(lineText, null);
	}

	@Override
	public ITokenizeLineResult tokenizeLine(final String lineText, @Nullable final IStateStack prevState) {
		return _tokenize(lineText, (StateStack) prevState, false);
	}

	@Override
	public ITokenizeLineResult2 tokenizeLine2(final String lineText) {
		return tokenizeLine2(lineText, null);
	}

	@Override
	public ITokenizeLineResult2 tokenizeLine2(final String lineText, @Nullable final IStateStack prevState) {
		return _tokenize(lineText, (StateStack) prevState, true);
	}

	@SuppressWarnings("unchecked")
	private <T> T _tokenize(
		String lineText,
		@Nullable StateStack prevState,
		final boolean emitBinaryTokens) {
		var rootId = this._rootId;
		if (rootId == null) {
			rootId = this._rootId = RuleFactory.getCompiledRuleId(
				this._grammar.getRepository().getSelf(),
				this,
				this._grammar.getRepository());
		}

		boolean isFirstLine;
		if (prevState == null || prevState.equals(StateStack.NULL)) {
			isFirstLine = true;
			final var rawDefaultMetadata = this._basicScopeAttributesProvider.getDefaultAttributes();
			final var defaultTheme = this.themeProvider.getDefaults();
			final int defaultMetadata = EncodedTokenAttributes.set(
				0,
				rawDefaultMetadata.languageId,
				rawDefaultMetadata.tokenType,
				null,
				defaultTheme.fontStyle,
				defaultTheme.foregroundId,
				defaultTheme.backgroundId);

			final var rootScopeName = this.getRule(rootId).getName(
				null,
				null);

			AttributedScopeStack scopeList;
			if (rootScopeName != null) {
				scopeList = AttributedScopeStack.createRootAndLookUpScopeName(
					rootScopeName,
					defaultMetadata,
					this);
			} else {
				scopeList = AttributedScopeStack.createRoot(
					"unknown",
					defaultMetadata);
			}

			prevState = new StateStack(
				null,
				rootId,
				-1,
				-1,
				false,
				null,
				scopeList,
				scopeList);
		} else {
			isFirstLine = false;
			prevState.reset();
		}

		if (lineText.isEmpty() || lineText.charAt(lineText.length() - 1) != '\n') {
			// Only add \n if the passed lineText didn't have it.
			lineText += '\n';
		}
		final var onigLineText = OnigString.of(lineText);
		final int lineLength = lineText.length();
		final var lineTokens = new LineTokens(
			emitBinaryTokens,
			lineText,
			_tokenTypeMatchers,
			balancedBracketSelectors);
		final var nextState = LineTokenizer.tokenizeString(
			this,
			onigLineText,
			isFirstLine,
			0,
			prevState,
			lineTokens,
			true);

		if (emitBinaryTokens) {
			return (T) new TokenizeLineResult2(lineTokens.getBinaryResult(nextState, lineLength), nextState);
		}
		return (T) new TokenizeLineResult(lineTokens.getResult(nextState, lineLength), nextState);
	}

	@Override
	@Nullable
	public String getName() {
		return _grammar.getName();
	}

	@Override
	public String getScopeName() {
		return rootScopeName;
	}

	@Override
	public Collection<String> getFileTypes() {
		return _grammar.getFileTypes();
	}
}
