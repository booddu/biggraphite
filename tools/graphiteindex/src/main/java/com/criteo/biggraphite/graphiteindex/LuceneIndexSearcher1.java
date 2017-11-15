package com.criteo.biggraphite.graphiteindex;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterator;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.index.Index;
import org.apache.cassandra.index.sasi.plan.QueryController;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.utils.AbstractIterator;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.automaton.RegExp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lucene-based Graphite metrics index.
 *
 * TODO(d.forest): provide support for globstar (**)
 *
 * TODO(d.forest): provide support for autocompletion
 */
public class LuceneIndexSearcher1 implements Index.Searcher, Closeable
{
    private static final Logger logger = LoggerFactory.getLogger(LuceneIndexSearcher1.class);

    private final ColumnFamilyStore baseCfs;
    private final ReadCommand readCommand;
    private final ColumnDefinition column;
    private final QueryController controller;

    /**
     * TODO(p.boddu): Add support to read from in-memory LuceneIndex. Including ControlledRealTimeReopenThread
     */
    public LuceneIndexSearcher1(ColumnFamilyStore baseCfs, ColumnDefinition column, ReadCommand readCommand)
        throws IOException
    {
        this.baseCfs = baseCfs;
        this.readCommand = readCommand;
        this.column = column;
        this.controller = new QueryController(baseCfs, (PartitionRangeReadCommand) readCommand, 10000);
    }

    @Override
    public UnfilteredPartitionIterator search(ReadExecutionController executionController) {
        ByteBuffer indexValueBb = readCommand.rowFilter().getExpressions().get(0).getIndexValue();
        String indexValue = UTF8Type.instance.compose(indexValueBb);
        logger.info("readCommand.rowFilter().getExpressions():"+ readCommand.rowFilter().getExpressions());
        logger.info("indexValue:"+ indexValue);
        BooleanQuery query = patternToQuery(indexValue);
        logger.info("baseCfs.getLiveSSTables().size():"+ baseCfs.getLiveSSTables().size());


        Map<SSTableReader, List<Long>> ssTableRowOffsets =
                baseCfs.getLiveSSTables().stream()
                        .map(ssTable -> {
                            String indexName = GraphiteSASI.makeIndexName(column, ssTable.descriptor.generation /*FIXME(p.boddu)*/);
                            Path indexPath = new File(ssTable.descriptor.directory /*FIXME(p.boddu)*/, indexName).toPath();
                            logger.info("indexPath:" + indexPath);
                            List<Long> offsets = searchOffsets(query, indexPath);
                            logger.info("offsets:" + offsets);
                            return Pair.of(ssTable, offsets);})
                        .collect(Collectors.toMap(t -> t.getLeft(), t -> t.getRight()));
        //return EmptyIterators.unfilteredPartition(baseCfs.metadata, false);
        return new LuceneResultsIterator(ssTableRowOffsets, readCommand, controller, executionController);
    }


    private static class LuceneResultsIterator extends AbstractIterator<UnfilteredRowIterator> implements UnfilteredPartitionIterator {
        private final ReadCommand readCommand;
        private final QueryController controller;
        private final ReadExecutionController executionController;
        private final Map<SSTableReader, List<Long>> ssTableRowOffsets;
        private final List<Long> o = Arrays.asList(531l, 1128l, 1250l);
        private int index = 0;

        public LuceneResultsIterator(Map<SSTableReader, List<Long>> ssTableRowOffsets,
                                     ReadCommand readCommand,
                                     QueryController controller,
                                     ReadExecutionController executionController) {
            this.ssTableRowOffsets = ssTableRowOffsets;
            this.readCommand = readCommand;
            this.executionController = executionController;
            this.controller = controller;
        }

        protected UnfilteredRowIterator computeNext() {
            if(index < o.size()) {
                try {
                    long offset = o.get(index++);
                    logger.info("Start loadingPartitionFor:" + offset);
                    SSTableReader ssTableReader = ssTableRowOffsets.keySet().stream().findFirst().get();
                    DecoratedKey decoratedKey = ssTableReader.keyAt(offset);
                    UnfilteredRowIterator uri = controller.getPartition(decoratedKey,
                            executionController);

                    while(uri.hasNext()) {
                        Unfiltered u = uri.next();
                        if(u.kind() == Unfiltered.Kind.ROW) {
                            Row r = (Row)u;
                            r.cells().forEach(c -> logger.info(
                                    String.format("Cell: %s;%s",
                                            c.column(),
                                            UTF8Type.instance.compose(c.value()))
                                    )
                            );
                        } else {
                            logger.info("NOT a row");
                        }
                    }

                    logger.info("Done loadingPartitionFor:" + uri);
                    return uri;
                } catch(IOException e) {
                    logger.error("Could not get partition:", e);
                }
                return null;
            }
            return null;
        }

        public void close() {

        }

        public boolean isForThrift()
        {
            return readCommand.isForThrift();
        }
        public CFMetaData metadata()
        {
            return readCommand.metadata();
        }
    }

    @Override
    public void close()
        throws IOException
    {
    }

    /*
    public boolean isInMemory() {
        return !indexPath.isPresent();
    }
    */

    public List<Long> searchOffsets(BooleanQuery query, Path indexPath)
    {
        return search(query, indexPath, LuceneUtils::getOffsetFromDocument);
    }

    private <T> List<T> search(BooleanQuery query, Path indexPath, Function<Document, T> handler)
    {
        logger.info("{} - Searching for generated query: {}", indexPath, query);

        ArrayList<T> results = new ArrayList<>();
        Collector collector = new MetricsIndexCollector(
            doc -> results.add(handler.apply(doc))
        );

        try {
            Directory directory = FSDirectory.open(indexPath);
            SearcherManager searcherMgr = new SearcherManager(directory, new SearcherFactory());
            IndexSearcher searcher = searcherMgr.acquire();
            try {
                // TODO(d.forest): we should probably use TimeLimitingCollector
                searcher.search(query, collector);
            } catch(IOException e) {
                logger.error("{} - Cannot finish search query: {}", indexPath, query, e);
            } finally {
                searcherMgr.release(searcher);
                searcherMgr.close();
                directory.close();
            }
        } catch(IOException e) {
            logger.error("{} - Cannot acquire index searcher", indexPath, e);
        }

        return results;
    }

    /**
     * Generates a Lucene query which matches the same metrics the given Graphite
     * globbing pattern would.
     */
    private BooleanQuery patternToQuery(String pattern)
    {
        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();

        int length = LuceneUtils.iterateOnElements(
            pattern,
            (element, depth) -> {
                // Wildcard-only fields do not filter anything.
                if (element == "*") {
                    return;
                }

                String termName = LuceneUtils.FIELD_PART_PREFIX + depth;
                Query query;
                if (element.indexOf('{') != -1 || element.indexOf('[') != -1) {
                    query = new RegexpQuery(
                        new Term(termName, graphiteToRegex(element)),
                        RegExp.NONE
                    );
                } else if (element.indexOf('*') != -1 || element.indexOf('?') != -1) {
                    query = new WildcardQuery(new Term(termName, element));
                } else {
                    query = new TermQuery(new Term(termName, element));
                }

                queryBuilder.add(query, BooleanClause.Occur.MUST);
            }
        );

        queryBuilder.add(
            IntPoint.newExactQuery(LuceneUtils.FIELD_LENGTH, length),
            BooleanClause.Occur.MUST
        );

        return queryBuilder.build();
    }

    /**
     * Naively transforms a Graphite globbing pattern into a regular expression.
     */
    private String graphiteToRegex(String query)
    {
        // TODO(d.forest): maybe make this less naive / checked?
        return query
            .replace('{', '(')
            .replace('}', ')')
            .replace(',', '|')
            .replace('?', '.')
            .replace("*", ".*?");
    }
}
