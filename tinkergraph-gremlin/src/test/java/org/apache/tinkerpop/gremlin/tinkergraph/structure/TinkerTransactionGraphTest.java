/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.tinkergraph.structure;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.TransactionException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class TinkerTransactionGraphTest {

    final Object vid = 100;

    ///// vertex tests

    @Test
    public void shouldReturnSameVertexInstanceInsideTransaction() {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();

        final GraphTraversalSource gtx = g.tx().begin();

        final Vertex vertex = gtx.addV().property(T.id, vid).next();
        assertEquals(vertex, gtx.V().next());
        assertEquals(vertex, gtx.V(vid).next());
        assertEquals(vertex, gtx.V().toList().get(0));
    }

    @Test
    public void shouldDeleteVertexOnCommit() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();

        final GraphTraversalSource gtx = g.tx().begin();

        gtx.addV().iterate();
        gtx.tx().commit();

        assertEquals(1, (long) gtx.V().count().next());

        gtx.V().drop().iterate();
        assertEquals(0, (long) gtx.V().count().next());

        countElementsInNewThreadTx(g, 1, 0);

        gtx.tx().commit();

        assertEquals(0, (long) gtx.V().count().next());

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(0L, (long) gtx3.V().count().next());

        countElementsInNewThreadTx(g, 0, 0);
    }

    @Test
    public void shouldDeleteCreatedVertexOnCommit() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();

        final GraphTraversalSource gtx = g.tx().begin();

        // create and drop Vertex in same transaction
        gtx.addV().property(T.id, vid).iterate();
        gtx.V(vid).drop().iterate();

        assertEquals(0, (long) gtx.V().count().next());
        countElementsInNewThreadTx(g, 0, 0);

        gtx.tx().commit();

        assertEquals(0, (long) gtx.V().count().next());
        countElementsInNewThreadTx(g, 0, 0);
        // should remove unused container
        assertEquals(0, g.getVertices().size());
    }


    @Test
    public void shouldDeleteUnusedVertexContainerOnCommit() {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();

        final GraphTraversalSource gtx = g.tx().begin();

        gtx.addV().property(T.id, vid).iterate();
        gtx.tx().commit();

        gtx.V(vid).drop().iterate();
        gtx.tx().commit();

        // should remove unused container
        assertEquals(0, g.getVertices().size());
    }

    @Test
    public void shouldDeleteUnusedVertexContainerOnRollback() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();

        final GraphTraversalSource gtx = g.tx().begin();

        gtx.addV().property(T.id, vid).iterate();
        gtx.tx().commit();

        gtx.V(vid).properties("test-property").iterate();
        // 1 container for existing vertex
        assertEquals(1, g.getVertices().size());

        // other tx try to add new vertices and modify existing
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();

            gtx2.addV().addV().iterate();
            gtx.V(vid).properties("test-property").iterate();

            // 1 container for existing vertex + 2 for new ones
            assertEquals(3, g.getVertices().size());

            assertEquals(3, (long) gtx2.V().count().next());

            gtx2.tx().rollback();
            // containers for new vertices should be removed
            assertEquals(1, g.getVertices().size());
        });
        thread.start();
        thread.join();

        gtx.tx().rollback();

        // should keep container because vertex not created in this transaction
        assertEquals(1, g.getVertices().size());
    }

    @Test
    public void shouldDeleteUnusedVertexContainerOnConcurrentVertexDelete() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();

        final GraphTraversalSource gtx = g.tx().begin();

        final TinkerVertex v1 = (TinkerVertex) gtx.addV().next();
        gtx.tx().commit();

        gtx.V(v1.id()).drop().iterate();

        // other tx try to remove same vertex
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();

            gtx2.V(v1.id()).drop().iterate();

            // should be ok
            assertEquals(0, (long) gtx2.V().count().next());
            assertEquals(0, (long) gtx2.E().count().next());

            gtx2.tx().commit();
        });
        thread.start();
        thread.join();

        // try do delete in initial tx
        try {
            gtx.tx().commit();
            fail("should throw TransactionException");
        } catch (TransactionException e) {

        }

        // should remove unused container
        assertEquals(0, g.getVertices().size());
    }

    ///// Edge tests

    @Test
    public void shouldRemoveContainerAndReferenceFromVertexOnDeleteEdge() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();

        final GraphTraversalSource gtx = g.tx().begin();

        final TinkerVertex v1 = (TinkerVertex) gtx.addV().next();
        final TinkerVertex v2 = (TinkerVertex) gtx.addV().next();
        gtx.addE("tests").from(v1).to(v2).next();
        gtx.tx().commit();

        assertEquals(2, (long) gtx.V().count().next());
        assertEquals(1, (long) gtx.E().count().next());

        gtx.E().hasLabel("tests").drop().iterate();

        assertEquals(2, (long) gtx.V().count().next());
        assertEquals(0, (long) gtx.E().count().next());

        // test count in other thread transaction
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(2L, (long) gtx2.V().count().next());
            assertEquals(1L, (long) gtx2.E().count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        assertEquals(2, (long) gtx.V().count().next());
        assertEquals(0, (long) gtx.E().count().next());

        // should remove reference from edge to parent vertices
        final TinkerVertex v1afterTx = g.getVertices().get(v1.id()).get();
        final TinkerVertex v2afterTx = g.getVertices().get(v2.id()).get();
        assertNull(v1afterTx.inEdgesId);
        assertEquals(0, v1afterTx.outEdgesId.get("tests").size());
        assertEquals(0, v2afterTx.inEdgesId.get("tests").size());
        assertNull(v2afterTx.outEdgesId);

        // should remove unused container
        assertEquals(0, g.getEdges().size());

        countElementsInNewThreadTx(g, 2, 0);
    }

    @Test
    public void shouldRemoveContainerAndReferenceFromVertexOnConcurrentDeleteEdge() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();

        final GraphTraversalSource gtx = g.tx().begin();

        final TinkerVertex v1 = (TinkerVertex) gtx.addV().next();
        final TinkerVertex v2 = (TinkerVertex) gtx.addV().next();
        gtx.addE("tests").from(v1).to(v2).next();
        gtx.tx().commit();

        assertEquals(2, (long) gtx.V().count().next());
        assertEquals(1, (long) gtx.E().count().next());

        gtx.E().hasLabel("tests").drop().iterate();

        assertEquals(2, (long) gtx.V().count().next());
        assertEquals(0, (long) gtx.E().count().next());

        // other tx try to remove same edge
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(2L, (long) gtx2.V().count().next());
            assertEquals(1L, (long) gtx2.E().count().next());

            // try to remove edge
            gtx2.E().hasLabel("tests").drop().iterate();

            // should be ok
            assertEquals(2, (long) gtx2.V().count().next());
            assertEquals(0, (long) gtx2.E().count().next());

            gtx2.tx().commit();
        });
        thread.start();
        thread.join();

        // try do delete in initial tx
        try {
            gtx.tx().commit();
            fail("should throw TransactionException");
        } catch (TransactionException e) {

        }

        // should remove unused container
        assertEquals(0, g.getEdges().size());

        assertEquals(2, (long) gtx.V().count().next());
        assertEquals(0, (long) gtx.E().count().next());

        // should remove reference from edge to parent vertices
        final TinkerVertex v1afterTx = g.getVertices().get(v1.id()).get();
        final TinkerVertex v2afterTx = g.getVertices().get(v2.id()).get();
        assertNull(v1afterTx.inEdgesId);
        assertEquals(0, v1afterTx.outEdgesId.get("tests").size());
        assertEquals(0, v2afterTx.inEdgesId.get("tests").size());
        assertNull(v2afterTx.outEdgesId);

        countElementsInNewThreadTx(g, 2, 0);
    }

    // index tests for vertex

    @Test
    public void shouldCreateIndexForNewVertex() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Vertex.class);

        final GraphTraversalSource gtx = g.tx().begin();

        gtx.addV().property(T.id, vid).property("test-property", 1).iterate();
        gtx.addV().property("test-property", 2).iterate();

        assertEquals(1L, (long) gtx.V().has("test-property", 1).count().next());
        assertEquals(vid, gtx.V().has("test-property", 1).id().next());
        assertEquals(0L, (long) gtx.V().has("test-property", 3).count().next());
        assertEquals(2L, (long) gtx.V().count().next());

        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(0L, (long) gtx2.V().has("test-property", 1).count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(1L, (long) gtx3.V().has("test-property", 1).count().next());
        assertEquals(vid, gtx3.V().has("test-property", 1).id().next());
        assertEquals(0L, (long) gtx3.V().has("test-property", 3).count().next());
        assertEquals(2L, (long) gtx3.V().count().next());

        countElementsInNewThreadTx(g, 2, 0);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.vertexIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(1, index.get(1).size());
        assertEquals(vid, index.get(1).iterator().next().get().id());
    }

    @Test
    public void shouldCreateIndexForNullVertexProperty() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Vertex.class);
        g.allowNullPropertyValues = true;
        final Integer nullValue = null;

        final GraphTraversalSource gtx = g.tx().begin();

        gtx.addV().property(T.id, vid).property("test-property", nullValue).iterate();
        gtx.addV().property("test-property", 2).iterate();

        assertEquals(1L, (long) gtx.V().has("test-property", nullValue).count().next());
        assertEquals(vid, gtx.V().has("test-property", nullValue).id().next());
        assertEquals(0L, (long) gtx.V().has("test-property", 1).count().next());
        assertEquals(2L, (long) gtx.V().count().next());

        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(0L, (long) gtx2.V().has("test-property", nullValue).count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(1L, (long) gtx3.V().has("test-property", nullValue).count().next());
        assertEquals(vid, gtx3.V().has("test-property", nullValue).id().next());
        assertEquals(0L, (long) gtx3.V().has("test-property", 1).count().next());
        assertEquals(2L, (long) gtx3.V().count().next());

        countElementsInNewThreadTx(g, 2, 0);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.vertexIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(1, index.get(AbstractTinkerIndex.IndexedNull.instance()).size());
        assertEquals(vid, index.get(AbstractTinkerIndex.IndexedNull.instance()).iterator().next().get().id());
    }

    @Test
    public void shouldRemoveIndexForRemovedVertex() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Vertex.class);

        final GraphTraversalSource gtx = g.tx().begin();

        gtx.addV().property(T.id, vid).property("test-property", 1).iterate();

        gtx.tx().commit();

        gtx.V(vid).drop().iterate();

        assertEquals(0L, (long) gtx.V().has("test-property", 1).count().next());
        assertEquals(0L, (long) gtx.V().count().next());

        // in another thread elements exists before commit
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(1L, (long) gtx2.V().has("test-property", 1).count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(0L, (long) gtx3.V().has("test-property", 1).count().next());
        assertEquals(0L, (long) gtx3.V().count().next());

        countElementsInNewThreadTx(g, 0, 0);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.vertexIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(0, index.size());
     }

    @Test
    public void shouldRemoveIndexForRemovedVertexProperty() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Vertex.class);

        final GraphTraversalSource gtx = g.tx().begin();

        gtx.addV().property(T.id, vid).property("test-property", 1).iterate();

        gtx.tx().commit();

        gtx.V(vid).properties("test-property").drop().iterate();

        assertEquals(0L, (long) gtx.V().has("test-property", 1).count().next());
        assertEquals(1L, (long) gtx.V().count().next());

        // in another thread elements exists before commit
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(1L, (long) gtx2.V().has("test-property", 1).count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(0L, (long) gtx3.V().has("test-property", 1).count().next());
        assertEquals(1L, (long) gtx3.V().count().next());

        countElementsInNewThreadTx(g, 1, 0);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.vertexIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(0, index.size());
    }

    // index tests for edge

    @Test
    public void shouldCreateIndexForNewEdge() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Edge.class);

        final GraphTraversalSource gtx = g.tx().begin();

        final TinkerVertex v1 = (TinkerVertex) gtx.addV().next();
        final TinkerVertex v2 = (TinkerVertex) gtx.addV().next();
        final TinkerEdge edge = (TinkerEdge) gtx.addE("tests").property("test-property", 1).from(v1).to(v2).next();

        assertEquals(1L, (long) gtx.E().has("test-property", 1).count().next());
        assertEquals(1L, (long) gtx.E().count().next());

        // in another thread index not updated yet
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(0L, (long) gtx2.E().has("test-property", 1).count().next());
            assertEquals(0L, (long) gtx2.E().count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(1L, (long) gtx.E().has("test-property", 1).count().next());
        assertEquals(1L, (long) gtx.E().count().next());

        countElementsInNewThreadTx(g, 2, 1);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.edgeIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(1, index.size());
        assertEquals(1, index.get(1).size());
        assertEquals(edge.id(), index.get(1).iterator().next().get().id());
    }

    @Test
    public void shouldChangeIndexForChangedEdge() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Edge.class);

        final GraphTraversalSource gtx = g.tx().begin();

        final TinkerVertex v1 = (TinkerVertex) gtx.addV().next();
        final TinkerVertex v2 = (TinkerVertex) gtx.addV().next();
        final TinkerEdge edge = (TinkerEdge) gtx.addE("tests").property("test-property", 1).from(v1).to(v2).next();
        gtx.tx().commit();

        gtx.E(edge.id()).property("test-property", 2).iterate();

        assertEquals(0L, (long) gtx.E().has("test-property", 1).count().next());
        assertEquals(1L, (long) gtx.E().has("test-property", 2).count().next());
        assertEquals(1L, (long) gtx.E().count().next());

        // in another thread index not updated yet
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(1L, (long) gtx2.E().has("test-property", 1).count().next());
            assertEquals(0L, (long) gtx2.E().has("test-property", 2).count().next());
            assertEquals(1L, (long) gtx2.E().count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(0L, (long) gtx.E().has("test-property", 1).count().next());
        assertEquals(1L, (long) gtx.E().has("test-property", 2).count().next());
        assertEquals(1L, (long) gtx.E().count().next());

        countElementsInNewThreadTx(g, 2, 1);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.edgeIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(1, index.size());
        assertNull(index.get(1));
        assertEquals(1, index.get(2).size());
        assertEquals(edge.id(), index.get(2).iterator().next().get().id());
    }

    @Test
    public void shouldRemoveIndexForRemovedEdge() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Edge.class);

        final GraphTraversalSource gtx = g.tx().begin();

        final TinkerVertex v1 = (TinkerVertex) gtx.addV().next();
        final TinkerVertex v2 = (TinkerVertex) gtx.addV().next();
        final TinkerEdge edge = (TinkerEdge) gtx.addE("tests").property("test-property", 1).from(v1).to(v2).next();
        gtx.tx().commit();

        gtx.E(edge.id()).drop().iterate();

        assertEquals(0L, (long) gtx.E().has("test-property", 1).count().next());
        assertEquals(0L, (long) gtx.E().count().next());

        // in another thread elements exists before commit
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(1L, (long) gtx2.E().has("test-property", 1).count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(0L, (long) gtx3.E().has("test-property", 1).count().next());
        assertEquals(0L, (long) gtx3.E().count().next());

        countElementsInNewThreadTx(g, 2, 0);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.edgeIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(0, index.size());
    }

    @Test
    public void shouldCreateIndexForNullProperty() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Edge.class);
        g.allowNullPropertyValues = true;
        final Integer nullValue = null;

        final GraphTraversalSource gtx = g.tx().begin();

        final TinkerVertex v1 = (TinkerVertex) gtx.addV().next();
        final TinkerVertex v2 = (TinkerVertex) gtx.addV().next();
        final TinkerEdge edge = (TinkerEdge) gtx.addE("tests").property("test-property", nullValue).
                from(v1).to(v2).next();

        assertEquals(1L, (long) gtx.E().has("test-property", nullValue).count().next());
        assertEquals(1L, (long) gtx.E().count().next());

        // in another thread index not updated yet
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(0L, (long) gtx2.E().has("test-property", nullValue).count().next());
            assertEquals(0L, (long) gtx2.E().count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(1L, (long) gtx3.E().has("test-property", nullValue).count().next());
        assertEquals(1L, (long) gtx3.E().count().next());

        countElementsInNewThreadTx(g, 2, 1);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.edgeIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(1, index.size());
        assertEquals(1, index.get(AbstractTinkerIndex.IndexedNull.instance()).size());
        assertEquals(edge.id(), index.get(AbstractTinkerIndex.IndexedNull.instance()).iterator().next().get().id());
    }

    @Test
    public void shouldRemoveIndexForNullProperty() throws InterruptedException {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        g.createIndex("test-property", Edge.class);
        final Integer nullValue = null;

        final GraphTraversalSource gtx = g.tx().begin();

        // create initial edge with indexed property
        final TinkerVertex v1 = (TinkerVertex) gtx.addV().next();
        final TinkerVertex v2 = (TinkerVertex) gtx.addV().next();
        final TinkerEdge edge = (TinkerEdge) gtx.addE("tests").property("test-property", 1).
                from(v1).to(v2).next();

        gtx.tx().commit();

        // remove property from index
        gtx.E(edge.id()).property("test-property", nullValue).iterate();

        assertEquals(0L, (long) gtx.E().has("test-property", 1).count().next());
        assertEquals(1L, (long) gtx.E().count().next());

        // in another thread index not updated yet
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx2 = g.tx().begin();
            assertEquals(1L, (long) gtx2.E().has("test-property", 1).count().next());
            assertEquals(1L, (long) gtx2.E().count().next());
        });
        thread.start();
        thread.join();

        gtx.tx().commit();

        final GraphTraversalSource gtx3 = g.tx().begin();
        assertEquals(0L, (long) gtx3.E().has("test-property", nullValue).count().next());
        assertEquals(1L, (long) gtx3.E().count().next());

        countElementsInNewThreadTx(g, 2, 1);

        final Map<Object, Set<TinkerElementContainer<?>>> index =
                (Map<Object, Set<TinkerElementContainer<?>>>) ((TinkerTransactionalIndex) g.edgeIndex).index.get("test-property");
        assertNotNull(index);
        // should be only vertex vid in set
        assertEquals(0, index.size());
    }

    // tests for cloning elements

    @Test
    public void vertexCloneTest() {
        final TinkerVertex vertex = new TinkerVertex(123, "label", TinkerTransactionGraph.open());
        final TinkerVertexProperty vp = new TinkerVertexProperty(vertex, "test", "qq");
        final TinkerEdge edge = new TinkerEdge(1, vertex, "label", vertex);
        vertex.properties = new ConcurrentHashMap<>();
        vertex.properties.put("test", new ArrayList<>());
        vertex.properties.get("test").add(vp);
        vertex.inEdgesId = new ConcurrentHashMap<>();
        vertex.inEdgesId.put("label", ConcurrentHashMap.newKeySet());
        vertex.inEdgesId.get("label").add(edge);

        final TinkerVertex copy = (TinkerVertex) vertex.clone();
        vertex.properties.get("test").remove(vp);
        assertEquals(1, copy.properties.get("test").size());
        vertex.inEdgesId.get("label").remove(edge);
        assertEquals(1, copy.inEdgesId.get("label").size());
    }

    @Test
    public void edgeCloneTest() {
        final TinkerTransactionGraph g = TinkerTransactionGraph.open();
        final TinkerVertex v1 = (TinkerVertex) g.addVertex();
        final TinkerVertex v2 = (TinkerVertex) g.addVertex();
        final TinkerEdge edge = new TinkerEdge(3, v1, "label", v2);
        final TinkerProperty property = new TinkerProperty(edge, "test", "qq");
        edge.properties = new ConcurrentHashMap<>();
        edge.properties.put(property.key(), property);

        final TinkerEdge copy = (TinkerEdge) edge.clone();
        edge.properties.remove(property.key());
        assertEquals(1, copy.properties.size());
    }

    // utility methods

    private void countElementsInNewThreadTx(final TinkerTransactionGraph g, final long verticesCount, final long edgesCount) throws InterruptedException {
        final Thread thread = new Thread(() -> {
            final GraphTraversalSource gtx = g.tx().begin();
            assertEquals(verticesCount, (long) gtx.V().count().next());
            assertEquals(edgesCount, (long) gtx.E().count().next());
        });
        thread.start();
        thread.join();
    }
}
