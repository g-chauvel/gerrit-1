// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.project;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.gerrit.server.project.RefPattern.isRE;

import com.google.common.flogger.FluentLogger;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.gerrit.common.data.ParameterizedString;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.server.CurrentUser;
import dk.brics.automaton.Automaton;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public abstract class RefPatternMatcher {
  public static RefPatternMatcher getMatcher(String pattern) {
    if (pattern.contains("${")) {
      return new ExpandParameters(pattern);
    } else if (isRE(pattern)) {
      //logger.atInfo().log("inside RefPatternMatcher getMatcher pattern:%s", pattern);
      return new Regexp(pattern);
    } else if (pattern.endsWith("/*")) {
      return new Prefix(pattern.substring(0, pattern.length() - 1));
    } else {
      return new Exact(pattern);
    }
  }

  public abstract boolean match(String ref, CurrentUser user);

  private static class Exact extends RefPatternMatcher {
	private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private final String expect;

    Exact(String name) {
      expect = name;
    }

    @Override
    public boolean match(String ref, CurrentUser user) {
	  logger.atInfo().log("match1");
      return expect.equals(ref);
    }
  }

  private static class Prefix extends RefPatternMatcher {
	private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private final String prefix;

    Prefix(String pfx) {
      prefix = pfx;
    }

    @Override
    public boolean match(String ref, CurrentUser user) {
      logger.atInfo().log("match2");
      return ref.startsWith(prefix);
    }
  }

  private static class Regexp extends RefPatternMatcher {
	private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private final Pattern pattern;
    private final String saveRe;

    Regexp(String re) {
	  saveRe = re;
      logger.atInfo().log("prepare match3 >%s<", re);
      pattern = Pattern.compile(re);
    }

    @Override
    public boolean match(String ref, CurrentUser user) {
	  boolean resultat = pattern.matcher(ref).matches();
	  logger.atInfo().log("match3 saveRe:>%s< ref:>%s< resultat:%s", saveRe, ref, resultat);
      return resultat || ref.equals(saveRe);
    }
  }

  public static class ExpandParameters extends RefPatternMatcher {
	private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private final ParameterizedString template;
    private final String prefix;

    ExpandParameters(String pattern) {
      template = new ParameterizedString(pattern);

      if (isRE(pattern)) {
        // Replace ${username} and ${shardeduserid} with ":PLACEHOLDER:"
        // as : is not legal in a reference and the string :PLACEHOLDER:
        // is not likely to be a valid part of the regex. This later
        // allows the pattern prefix to be clipped, saving time on
        // evaluation.
        Map<String, String> params =
            ImmutableMap.of(
                RefPattern.USERID_SHARDED, "\\$\\{"+RefPattern.USERID_SHARDED+"\\}",
                RefPattern.USERNAME, "\\$\\{"+RefPattern.USERNAME+"\\}");
        Automaton am = RefPattern.toRegExp(template.replace(params)).toAutomaton();
        String rePrefix = am.getCommonPrefix();
        prefix = rePrefix.substring(0, rePrefix.indexOf("${"));
        logger.atInfo().log("ExpandParameters pattern:%s prefix:%s", pattern, prefix);
      } else {
        prefix = pattern.substring(0, pattern.indexOf("${"));
      }
    }

    @Override
    public boolean match(String ref, CurrentUser user) {
	  logger.atInfo().log("match4 ENTRY");
      if (!(ref.startsWith(prefix) || ref.substring(1).startsWith(prefix))) {
		logger.atInfo().log("match4 EXIT1 false");
        return false;
      }

      for (String username : getUsernames(user)) {
        String u;
        if (isRE(template.getPattern())) {
          u = Pattern.quote(username);
        } else {
          u = username;
        }
		logger.atInfo().log("match4 MAIN1 u:%s user:%s", u, user);
        Account.Id accountId = user.isIdentifiedUser() ? user.getAccountId() : null;
        RefPatternMatcher next = getMatcher(expand(template, u, accountId));
        if (next != null && next.match(expand(ref, u, accountId), user)) {
		  logger.atInfo().log("match4 EXIT2 true");
          return true;
        }
      }
      logger.atInfo().log("match4 EXIT3 false");
      return false;
    }

    private ImmutableSet<String> getUsernames(CurrentUser user) {
      Stream<String> usernames = Streams.stream(user.getUserName());
      if (user.isIdentifiedUser()) {
        usernames = Streams.concat(usernames, user.asIdentifiedUser().getEmailAddresses().stream());
      }
      return usernames.collect(toImmutableSet());
    }

    public boolean matchPrefix(String ref) {
	  String reff = ref;
	  if (isRE(ref)) {
		  reff = ref.substring(1);
	  }
	  logger.atInfo().log("matchPrefix ref:%s reff:%s prefix:%s", ref, reff, prefix);
      return reff.startsWith(prefix);
    }

    private String expand(String parameterizedRef, String userName, Account.Id accountId) {
      if (parameterizedRef.contains("${")) {
        return expand(new ParameterizedString(parameterizedRef), userName, accountId);
      }
      return parameterizedRef;
    }

    private String expand(
        ParameterizedString parameterizedRef, String userName, Account.Id accountId) {
	  logger.atInfo().log("expand ENTRY");
      Map<String, String> params = new HashMap<>();
      params.put(RefPattern.USERNAME, userName);
      if (accountId != null) {
        params.put(RefPattern.USERID_SHARDED, RefNames.shard(accountId.get()));
      }
      return parameterizedRef.replace(params);
    }
  }
}
