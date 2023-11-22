package io.github.t45k.r2dbc_aop_trial

import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.runBlocking
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.jooq.impl.DSL
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
@RestController
@EnableR2dbcRepositories
@EnableWebFluxSecurity
@EnableMethodSecurity
class R2dbcAopTrialApplication(
    private val jooqRepo: JooqRepo,
) {
    @GetMapping("nonblocking")
    suspend fun nonBlocking(): Long = jooqRepo.count()

    @GetMapping("blocking/aop")
    suspend fun aop(): Long = jooqRepo.count()

    @GetMapping("blocking/security")
    @PreAuthorize("@authorizer.auth()")
    suspend fun security(): Long = jooqRepo.count()


    @GetMapping("blocking/both")
    @PreAuthorize("@authorizer.auth()")
    suspend fun both(): Long = jooqRepo.count()
}

@Component
class Authorizer(private val repo: JooqRepo) {
    fun auth(): Boolean = runBlocking {
        println("through auth")
        repo.count()
        true
    }
}

@Component
@Aspect
class Aspect(private val repo: JooqRepo) {
    @Before("execution(* aop(..)) or execution(* both(..))")
    fun execute(joinPoint: JoinPoint) = runBlocking {
        println("through aop")
        repo.count()
    }
}

@Repository
class JooqRepo(connectionFactory: ConnectionFactory) {
    private val dslContext = DSL.using(connectionFactory).dsl()

    suspend fun count(): Long = dslContext.query("select count(*) from batch").awaitFirst().toLong()
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
