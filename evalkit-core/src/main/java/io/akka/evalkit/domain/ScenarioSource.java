package io.akka.evalkit.domain;

import java.util.List;

/**
 * Where a campaign's scenarios come from.
 *
 * <p>Paged rather than handed over whole, because a campaign of five thousand scenarios
 * cannot live in a workflow's state: state is serialised on every transition, and carrying
 * the corpus through each one would cost more than running it.
 *
 * <p>The workflow therefore holds a cursor and asks for the next page. That is also what
 * makes a restart cheap &mdash; recovery reads a number, not a corpus.
 */
public interface ScenarioSource {

    /** How many scenarios this campaign has, so progress means something. */
    int size(String campaignId);

    /**
     * A page, from {@code offset}, at most {@code limit} long.
     *
     * <p>Must be stable: the same offset returns the same scenarios. A source that
     * reordered between waves would let a restart skip some and repeat others, and the
     * counts would still look plausible.
     */
    List<Scenario> page(String campaignId, int offset, int limit);
}
