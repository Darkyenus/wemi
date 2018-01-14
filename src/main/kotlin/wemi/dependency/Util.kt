package wemi.dependency

import wemi.boot.CLI
import wemi.util.TreeNode
import wemi.util.printTree

/**
 * Returns a pretty-printed string in which the system is displayed as a tree of dependencies.
 * Uses full range of unicode characters for clarity.
 */
fun Map<DependencyId, ResolvedDependency>.prettyPrint(explicitRoots: Collection<DependencyId>?): CharSequence {
    /*
    ╤ org.foo:proj:1.0 ✅
    │ ╘ com.bar:pr:2.0 ❌⛔️
    ╞ org.foo:proj:1.0 ✅⤴
    ╘ com.baz:pr:2.0 ❌⛔️

    Status symbols:
    OK ✅
    Error ❌⛔️
    Missing ❓
    Already shown ⤴
     */
    val StatusNormal: Byte = 0
    val StatusNotResolved: Byte = 1
    val StatusCyclic: Byte = 2

    class NodeData(val dependencyId: DependencyId, var status: Byte)

    val nodes = HashMap<DependencyId, TreeNode<NodeData>>()

    // Build nodes
    for (depId in keys) {
        nodes.put(depId, TreeNode(NodeData(depId, StatusNormal)))
    }

    // Connect nodes (even with cycles)
    nodes.forEach { depId, node ->
        this@prettyPrint[depId]?.dependencies?.forEach { dep ->
            var nodeToConnect = nodes[dep.dependencyId]
            if (nodeToConnect == null) {
                nodeToConnect = TreeNode(NodeData(dep.dependencyId, StatusNotResolved))
                nodes[dep.dependencyId] = nodeToConnect
            }
            node.add(nodeToConnect)
        }
    }

    val remainingNodes = HashMap(nodes)

    fun liftNode(dependencyId: DependencyId): TreeNode<NodeData> {
        // Lift what was asked
        val liftedNode = remainingNodes.remove(dependencyId) ?: return TreeNode(NodeData(dependencyId, StatusCyclic))
        val resultNode = TreeNode(liftedNode.value)
        // Lift all dependencies too and return them in the result node
        for (dependencyNode in liftedNode) {
            resultNode.add(liftNode(dependencyNode.value.dependencyId))
        }
        return resultNode
    }

    val roots = ArrayList<TreeNode<NodeData>>()

    // Lift explicit roots
    explicitRoots?.forEach { root ->
        val liftedNode = liftNode(root)
        // Check for nodes that are in explicitRoots but were never resolved to begin with
        if (liftedNode.value.status == StatusCyclic && !this.containsKey(liftedNode.value.dependencyId)) {
            liftedNode.value.status = StatusNotResolved
        }
        roots.add(liftedNode)
    }

    // Lift implicit roots
    for (key in this.keys) {
        if (remainingNodes.containsKey(key)) {
            roots.add(liftNode(key))
        }
    }

    // Lift rest as roots
    while (remainingNodes.isNotEmpty()) { //This should never happen?
        val (dependencyId, _) = remainingNodes.iterator().next()
        roots.add(liftNode(dependencyId))
    }

    // Now we can start printing!

    return printTree(roots) { result ->
        val dependencyId = this.dependencyId

        result.append(dependencyId.group).append(':').append(dependencyId.name).append(':').append(dependencyId.version)
        for ((key, value) in dependencyId.attributes) {
            result.append(' ').append(key.name).append('=').append(value)
        }
        result.append(' ')

        val resolved = this@prettyPrint[dependencyId]

        when {
            resolved == null -> result.append(CLI.ICON_UNKNOWN)
            resolved.hasError -> result.append(CLI.ICON_FAILURE)
            else -> result.append(CLI.ICON_SUCCESS)
        }

        if (status == StatusCyclic) {
            result.append(CLI.ICON_SEE_ABOVE)
        } else {
            val resolvedFrom = resolved?.resolvedFrom
            if (resolvedFrom != null) {
                result.append(" from ").append(resolvedFrom)
            }
        }
    }
}