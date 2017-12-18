package wemi

/**
 *
 */
class InputBase (val interactive:Boolean) : Input {

}

class InputExtension : Input {

}

interface Input {

}

fun <Result> Scope.withInput(entries:Pair<String, String>, action:Scope.()->Result):Result {
    return using({

    }, parent = null, action = action)
}