/**
 * Classes in this package will be injected as JVM agent into the process that will be hotswapped.
 *
 * Therefore, they MUST NOT depend on any other libraries, than what is available on vanilla JVM.
 */
package wemiplugin.jvmhotswap.agent;