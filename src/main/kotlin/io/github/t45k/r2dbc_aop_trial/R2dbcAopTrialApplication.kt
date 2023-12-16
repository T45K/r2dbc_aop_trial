package io.github.t45k.r2dbc_aop_trial

import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitExchangeOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@SpringBootApplication
@RestController
@EnableR2dbcRepositories
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
class R2dbcAopTrialApplication(
    private val jooqRepo: JooqRepo,
) {
    private val webClient = WebClient.create()

    @GetMapping("nonblocking")
    suspend fun nonBlocking(): Long = jooqRepo.count()

    @GetMapping("blocking/aop")
    suspend fun aop(): String {
        webClient.get().uri("http://localhost:8080/nonblocking")
            .awaitExchangeOrNull { println(it) }

        return Thread.currentThread().name
    }

    @GetMapping("blocking/security")
    @PreAuthorize("@authorizer.auth()")
    suspend fun security(): Long = jooqRepo.count()

    @GetMapping("blocking/both")
    @PreAuthorize("@authorizer.auth()")
    suspend fun both(): Long = jooqRepo.count()
}

@Component
@Configuration
class Authorizer(private val repo: JooqRepo) {
    fun auth() = mono {
        println("through auth")
        repo.count()
        true
    }
}

val ProceedingJoinPoint.coroutineContinuation: Continuation<Any?>
    get() = this.args.last() as Continuation<Any?>

val ProceedingJoinPoint.coroutineArgs: Array<Any?>
    get() = this.args.sliceArray(0 until this.args.size - 1)

suspend fun ProceedingJoinPoint.proceedCoroutine(
    args: Array<Any?> = this.coroutineArgs,
): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
    this.proceed(args + continuation)
}

fun ProceedingJoinPoint.runCoroutine(
    block: suspend () -> Any?,
): Any? =
    block.startCoroutineUninterceptedOrReturn(this.coroutineContinuation)

@Component
@Aspect
class Aspect(private val repo: JooqRepo) {
    @Around("execution(* aop(..)) || execution(* both(..))")
    fun execute(joinPoint: ProceedingJoinPoint): Any? {
        return joinPoint.runCoroutine {
            repo.count()
            println("aop")
            joinPoint.proceedCoroutine()!!
        }
    }

    suspend fun dummy() {
        suspendCoroutineUninterceptedOrReturn<Any> { }
        suspend { }.startCoroutineUninterceptedOrReturn(TODO())
    }
}

@Repository
class JooqRepo(connectionFactory: ConnectionFactory) {
    private val dslContext = DSL.using(connectionFactory).dsl()

    suspend fun count(): Long = dslContext.query("select count(*) from batch").awaitSingle().toLong()
}

@Configuration
class WebSecurityConfig {
    @Bean
    fun securityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http.authorizeExchange { it.anyExchange().permitAll() }.build()
}

fun main(args: Array<String>) {
    runApplication<R2dbcAopTrialApplication>(*args)
}
