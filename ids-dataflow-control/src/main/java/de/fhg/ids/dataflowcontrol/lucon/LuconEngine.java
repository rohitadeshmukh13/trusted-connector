/*-
 * ========================LICENSE_START=================================
 * ids-dataflow-control
 * %%
 * Copyright (C) 2018 Fraunhofer AISEC
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package de.fhg.ids.dataflowcontrol.lucon;

import alice.tuprolog.*;
import alice.tuprolog.event.LibraryEvent;
import alice.tuprolog.event.LibraryListener;
import de.fhg.aisec.ids.api.router.CounterExample;
import de.fhg.aisec.ids.api.router.RouteVerificationProof;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LUCON (Logic based Usage Control) policy decision engine.
 *
 * <p>This engine uses tuProlog as a logic language implementation to answer policy decision
 * requests.
 *
 * @author Julian Schuette (julian.schuette@aisec.fraunhofer.de)
 */
public class LuconEngine {
  private static final Logger LOG = LoggerFactory.getLogger(LuconEngine.class);

  // A Prolog query to compute a path from X to Y in a graph of statements (= a route)
  private static final String QUERY_ROUTE_VERIFICATION = "entrynode(X), stmt(Y), path(X, Y, T).";
  private Prolog p;

  /**
   * Create a new LuconEngine which writes to a given output stream.
   *
   * @param out OutputStream to write Prolog engine outputs to, or null if output should not
   *     printed.
   */
  public LuconEngine(@Nullable OutputStream out) {
    p = new Prolog();

    // Add some listeners for logging/debugging
    p.addExceptionListener(ex -> LOG.error("Exception in Prolog reasoning: " + ex.getMsg()));
    p.addQueryListener(q -> LOG.trace("Prolog query " + q.getSolveInfo().getQuery().toString()));
    p.addLibraryListener(
        new LibraryListener() {
          @Override
          public void libraryLoaded(LibraryEvent e) {
            LOG.debug("Prolog library loaded " + e.getLibraryName());
          }

          @Override
          public void libraryUnloaded(LibraryEvent e) {
            LOG.debug("Prolog library unloaded " + e.getLibraryName());
          }
        });
    p.addSpyListener(l -> LOG.trace(l.getMsg() + " " + l.getSource()));
    p.addWarningListener(
        w -> {
          if (!w.getMsg().contains("The predicate false/0 is unknown")) {
            LOG.warn(w.getMsg());
          }
        });
    p.addOutputListener(
        l -> {
          if (out != null) {
            try {
              out.write(l.getMsg().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
              LOG.error(e.getMessage(), e);
            }
          }
        });
    try {
      p.loadLibrary(new LuconLibrary());
    } catch (InvalidLibraryException e) {
      // should never happen
      throw new RuntimeException("Error loading " + LuconLibrary.class.getName(), e);
    }
  }

  @SuppressWarnings("unused")
  public void setSpy(boolean spy) {
    p.setSpy(spy);
  }

  /**
   * Loads a policy in form of a prolog theory.
   *
   * <p>Existing policies will be overwritten.
   *
   * @param is The input stream from which to load the Theory
   * @throws InvalidTheoryException Syntax error in Prolog document
   * @throws IOException I/O error reading from stream
   */
  public void loadPolicy(@Nullable InputStream is) throws InvalidTheoryException, IOException {
    if (is == null) {
      return;
    }
    Theory t = new Theory(is);
    LOG.debug("Loading theory:\n" + t.toString());
    p.setTheory(t);
  }

  @NonNull
  public List<SolveInfo> query(@Nullable String query, boolean findAll)
      throws NoMoreSolutionException, MalformedGoalException {
    LOG.debug("Running Prolog query: " + query);
    @NonNull List<SolveInfo> result = query(p, query, findAll);
    if (LOG.isTraceEnabled()) {
      try {
        for (SolveInfo i : result) {
          LOG.trace("Result is {}", i.getSolution().toString());
        }
      } catch (NoSolutionException e) {
        e.printStackTrace();
      }
    }
    return result;
  }

  @NonNull
  private List<SolveInfo> query(@NonNull Prolog engine, @Nullable String query, boolean findAll)
      throws NoMoreSolutionException, MalformedGoalException {
    List<SolveInfo> result = new ArrayList<>();
    if (query == null) {
      return result;
    }
    SolveInfo solution = engine.solve(query);
    while (solution.isSuccess()) {
      result.add(solution);
      if (findAll && engine.hasOpenAlternatives()) {
        solution = engine.solveNext();
      } else {
        break;
      }
    }
    engine.solveEnd();
    return result;
  }

  @NonNull
  public String getTheory() {
    Theory t = p.getTheory();
    return t == null ? "" : t.toString();
  }

  @NonNull
  public String getTheoryAsJSON() {
    Theory t = p.getTheory();
    return t == null ? "" : t.toJSON();
  }

  /**
   * Returns "true" if the given route is valid under all policies or returns a set of
   * counterexamples.
   *
   * @param id Route id
   * @param routePl The route, represented as Prolog
   * @return A list of counterexamples which violate the rule or empty, if no route violates the
   *     policy.
   */
  public RouteVerificationProof proofInvalidRoute(@Nullable String id, @Nullable String routePl) {
    // The proof object we will return
    RouteVerificationProof proof = new RouteVerificationProof(id);

    // Just for information: save the query we used to generate the proof
    proof.setQuery(QUERY_ROUTE_VERIFICATION);

    try {
      // Get policy as prolog, add Camel route and init new Prolog engine with combined theory
      Theory t = p.getTheory();
      t.append(new Theory(routePl));
      Prolog newP = new Prolog();
      newP.loadLibrary(new LuconLibrary());
      newP.setTheory(t);

      // Generate the proof (=run query)
      List<SolveInfo> result = query(newP, QUERY_ROUTE_VERIFICATION, true);

      // If a result has been found, this means there is at least one counterexample of a path in a
      // route that violates a policy
      if (!result.isEmpty()) {
        List<CounterExample> ces = new ArrayList<>(result.size());
        result.forEach(
            s -> {
              try {
                ces.add(new CounterExampleImpl(s.getVarValue("T")));
              } catch (NoSolutionException nse) {
                // This cannot happen if our code wasn't badly screwed up!
                throw new RuntimeException(nse);
              }
            });
        proof.setCounterExamples(ces);
        proof.setValid(false);
      }
    } catch (InvalidTheoryException
        | InvalidLibraryException
        | NoMoreSolutionException
        | MalformedGoalException e) {
      LOG.error(e.getMessage(), e);
    }
    return proof;
  }
}
