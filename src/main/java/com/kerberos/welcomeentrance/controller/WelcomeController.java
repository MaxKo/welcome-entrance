package com.kerberos.welcomeentrance.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.function.Supplier;

import static com.fasterxml.jackson.databind.SerializationFeature.*;

@RestController
@Slf4j
@RequiredArgsConstructor
public class WelcomeController {


    private final ObjectMapper mapper = new ObjectMapper();

    {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(FAIL_ON_EMPTY_BEANS);
        mapper.enable(INDENT_OUTPUT);
    }

    boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    @GetMapping(value = "/", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> welcome(ServerHttpRequest request) {
        return Flux.concat(request(request),
                getNetworkIPs()
                .parallel(255)
                .runOn(Schedulers.newParallel("custom: ", 255))
                .map(Supplier::get)
                .filter(StringUtils::hasText)
                .sequential());
    }

    @SneakyThrows
    private String toJson(Object o){
        return mapper.writeValueAsString(o);
    }

    Flux<String> request (ServerHttpRequest request) {
        return Flux.just(request.getHeaders(), request.getRemoteAddress(), toJson(request))
                .map(Object::toString)
                .doOnEach(System.out::println);
    }


    @SneakyThrows
    public Flux<Supplier<String>> getNetworkIPs() {
        final byte[] ip;
        try {
            ip = InetAddress.getLocalHost().getAddress();
        } catch (Exception e) {
            return Flux.from(Mono.fromSupplier(this::notAvailableSupplier));     // exit method, otherwise "ip might not have been initialized"
        }

        return Flux.range(1, 254)
                .map(i -> monitorIpAddressSupplier(ip.clone(), i));
    }

    private Supplier<String> notAvailableSupplier() {
        return () -> "network not available";
    }

    private Supplier<String> monitorIpAddressSupplier(byte[] ip, int j) {
        return () -> {
            String ret = "";
            InetAddress address = null;
            try {
                ip[3] = (byte) j;

                address = InetAddress.getByAddress(ip);
                String output = address.toString().substring(1);

                if (address.isReachable(5000)) {
                    ret = (output + " is on the network ") + address.getHostName() + " " + address.getCanonicalHostName() + " " + address.getHostAddress() + " ";

                    ret += getMacAddrHost(address.getHostAddress());
                }

                log.info(j + "="+(address.getAddress()[3] & 0xFF)  + ": " + ret +" " + Thread.currentThread().getName() + " " + address.getHostAddress()  );

                if ((byte)j != address.getAddress()[3]) ret = "!!!CONCURRENT ERROR!!!" + ret;
            } catch (Exception e) {
                log.error(address.getHostAddress() + " " + e.getMessage());
            } finally {
                return ret;
            }
        };
    }



    public String getMacAddrHost(String host) throws IOException, InterruptedException {
        if (!ping3(host)) return null;

        InetAddress address = InetAddress.getByName(host);
        String ip = address.getHostAddress();
        return run_program_with_catching_output("arp -a " + ip);
    }


    public boolean ping3(String host) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder("ping", isWindows ? "-n" : "-c", "1", host);
        Process proc = processBuilder.start();

        int returnVal = proc.waitFor();

        return returnVal == 0;
    }

    public  String run_program_with_catching_output(String param) throws IOException {
        Process p = Runtime.getRuntime().exec(param);

        return new BufferedReader(new InputStreamReader(p.getInputStream()))
                .lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .peek(System.out::println)
                .map(l -> l.substring(1))
                .map(this::extractMacAddr)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);

    }

    public  String extractMacAddr(String str) {
        return Arrays.stream(str.split("   "))
                .map(String::trim)
                .filter(s -> s.length() == 17)
                .map(String::toUpperCase)
                .findFirst()
                .orElse("");
    }
}
