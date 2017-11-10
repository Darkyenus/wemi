# Things to do next
1. Change how requested scopes work. Currently, the scopes are appended, one by one.
Change this into two "stacks" of scope, standard and override scope. Standard stack is on the left and grows to the right.
Override stack is on the right and grows to the left. This allows to selectively override any key, even if the used key
pushes its own scope. The syntax could be like this: `project/normalLow:normalHigh::overrideLow:overrideHigh:key`.
Pushing into the override could be using a new method `withOverride`. Questions:
	- Is this the most flexible approach? Would not it be better to allow some sort of "surgical override"?
	- Or allow key scoping? "Stacktrace" scoping?
	- Or maybe mutable configurations? Temporary anonymous override configurations?
	- Studies:
		- Simple: Key COMP uses config A to retrieve key IMP, we want to change what IMP value in A for COMP is
		- Medium: like simple, but COMP then also retrieves that IMP in its own scope/scope B and we don't want to modify that/modify that differently
		- Hard: like medium, but all retrievals of IMP are from different sub-tasks and for some we don't want to modify IMP and for some we do
	- Questions from studies:
		- Do we allow the key to have different value based on a key?
			- Is the key part of the "scope"?
			- Is only the immediate key or key stack part of the scope?
	- Proposal: Key invoked becomes part of the `Scope`, and it will be possible to declare override "scope matchers"
	at the project level, which will activate when the overridden key is requested at the correct scope.
	Should there be "override priority levels" to determine which override should be used?

**Above has been implemented, document `extend` mechanism!!!**

1. Allow projects to inherit from parent project configuration/config set
	- Maybe simple "setup function" would be enough...

1. IDE (IntelliJ, other Jetbrains later) integrations

1. Allow to build IntelliJ plugins: https://github.com/JetBrains/gradle-intellij-plugin

1. Self hosting

1. Trace debug mode

1. Allow projects to depend on other projects and implement this in IDE plugin