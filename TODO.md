# Things to do next
1. Change how requested scopes work. Currently, the scopes are appended, one by one.
Change this into two "stacks" of scope, standard and override scope. Standard stack is on the left and grows to the right.
Override stack is on the right and grows to the left. This allows to selectively override any key, even if the used key
pushes its own scope. The syntax could be like this: `project/normalLow:normalHigh::overrideLow:overrideHigh:key`.
Pushing into the override could be using a new method `withOverride`. Questions:
	- Is this the most flexible approach? Would not it be better to allow some sort of "surgical override"?
	- Or allow key scoping? "Stacktrace" scoping?
	- Or maybe mutable configurations? Temporary anonymous override configurations?