What if configurations
	- were not assigned to project
	- but could be
	- were scoped to single project - with default global
	
project/test,debug:run

Configuration - may have parent configuration

val normalEyes by configuration {
	plain old global configuration layer
}

val specialEyes by configuration(extends:normalEyes) {
	plain old global configuration layer, which inherits all keys of normalEyes
}

val shepardProject by project {
	specialEyes extend {
		configuration actually used when specialEyes if requested to be applied to the shepardProject
		inherits all keys of specialEyes
		all keys are 
		some key settings...
	}
	
	normal stuff
}

shepardProject/specialEyes:debug:test:run

Key eval syntax:
(<project>"/")?(<config>":")*<key>

Collection MUST be first declared globally and only then it can be specified.

Project can be present, but does not have to be if default project is set, it will be used instead.
None or multiple configs can be used, each only once.
Which key is used is defined by order - closer to the key name = more priority.
If "test" config has the key, it is used and search is ended. If "debug", that is used.
Then "specialEyes", then project and finally default value.
If the key does not have a default value, its evaluation fails.

Collection values are slightly more complicated, doing += just appends the value to the current value and the value that
would be used if this key was not set in this scope.
"More buried value is, earlier in the list it is."

However this chain works only by using +=, (maybe append and -= / remove in the future), doing hard "set" ends the chain.