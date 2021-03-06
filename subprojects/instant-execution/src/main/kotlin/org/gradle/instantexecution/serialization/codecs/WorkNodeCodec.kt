/*
 * Copyright 2019 the original author or authors.
 *
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
 */

package org.gradle.instantexecution.serialization.codecs

import org.gradle.api.internal.GradleInternal
import org.gradle.execution.plan.Node
import org.gradle.execution.plan.TaskNode
import org.gradle.instantexecution.serialization.Codec
import org.gradle.instantexecution.serialization.IsolateOwner
import org.gradle.instantexecution.serialization.ReadContext
import org.gradle.instantexecution.serialization.WriteContext
import org.gradle.instantexecution.serialization.readCollection
import org.gradle.instantexecution.serialization.readNonNull
import org.gradle.instantexecution.serialization.withIsolate
import org.gradle.instantexecution.serialization.writeCollection


internal
class WorkNodeCodec(
    private val owner: GradleInternal,
    private val internalTypesCodec: Codec<Any?>
) {

    suspend fun WriteContext.writeWork(nodes: List<Node>) {
        // Share bean instances across all nodes (except tasks, which have their own isolate)
        withIsolate(IsolateOwner.OwnerGradle(owner), internalTypesCodec) {
            writeNodes(nodes)
        }
    }

    suspend fun ReadContext.readWork(): List<Node> =
        withIsolate(IsolateOwner.OwnerGradle(owner), internalTypesCodec) {
            readNodes()
        }

    private
    suspend fun WriteContext.writeNodes(nodes: List<Node>) {
        val scheduledNodeIds = nodes.asSequence().mapIndexed { index, node -> node to index }.toMap()
        writeSmallInt(nodes.size)
        for (node in nodes) {
            writeNode(node, scheduledNodeIds)
        }
    }

    private
    suspend fun ReadContext.readNodes(): List<Node> {
        val count = readSmallInt()
        val nodesById = HashMap<Int, Node>(count)
        val nodes = ArrayList<Node>(count)
        for (i in 0 until count) {
            nodes.add(readNode(nodesById))
        }
        return nodes
    }

    private
    suspend fun WriteContext.writeNode(
        node: Node,
        scheduledNodeIds: Map<Node, Int>
    ) {
        val nodeId = scheduledNodeIds.getValue(node)
        writeSmallInt(nodeId)
        write(node)

        writeSuccessorReferencesOf(node, scheduledNodeIds)
    }

    private
    suspend fun ReadContext.readNode(nodesById: MutableMap<Int, Node>): Node {
        val nodeId = readSmallInt()
        val node = readNonNull<Node>()
        readSuccessorReferencesOf(node, nodesById)
        node.dependenciesProcessed()
        nodesById[nodeId] = node
        return node
    }

    private
    fun WriteContext.writeSuccessorReferencesOf(node: Node, scheduledNodeIds: Map<Node, Int>) {
        writeSuccessorReferences(node.dependencySuccessors, scheduledNodeIds)
        when (node) {
            is TaskNode -> {
                writeSuccessorReferences(node.shouldSuccessors, scheduledNodeIds)
                writeSuccessorReferences(node.mustSuccessors, scheduledNodeIds)
                writeSuccessorReferences(node.finalizingSuccessors, scheduledNodeIds)
            }
        }
    }

    private
    fun ReadContext.readSuccessorReferencesOf(node: Node, nodesById: MutableMap<Int, Node>) {
        readSuccessorReferences(nodesById) {
            node.addDependencySuccessor(it)
        }
        when (node) {
            is TaskNode -> {
                readSuccessorReferences(nodesById) {
                    node.addShouldSuccessor(it)
                }
                readSuccessorReferences(nodesById) {
                    require(it is TaskNode)
                    node.addMustSuccessor(it)
                }
                readSuccessorReferences(nodesById) {
                    require(it is TaskNode)
                    node.addFinalizingSuccessor(it)
                }
            }
        }
    }

    private
    fun WriteContext.writeSuccessorReferences(
        successors: Collection<Node>,
        scheduledNodeIds: Map<Node, Int>
    ) {
        writeCollection(successors.mapNotNull(scheduledNodeIds::get)) {
            writeSmallInt(it)
        }
    }

    private
    fun ReadContext.readSuccessorReferences(nodesById: Map<Int, Node>, onSuccessor: (Node) -> Unit) {
        readCollection {
            val successorId = readSmallInt()
            val successor = nodesById.getValue(successorId)
            onSuccessor(successor)
        }
    }
}
