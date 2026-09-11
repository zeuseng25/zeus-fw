package com.zeus.framework.ai;

/**
 * ZeusCapabilityVerifierTest için sahte işaretçi sınıf.
 *
 * zeus-ai modülünün GERÇEK ZeusAiAutoConfiguration sınıfıyla aynı TAM ADI taşır ama onunla
 * ilgisi yoktur — yalnızca bu test kaynağında yaşar. Gerekçe: ZeusCapabilityVerifier, bir
 * yeteneğin WAR'da olup olmadığını classLoader.loadClass(...) ile GERÇEK sınıf çözümlemesiyle
 * anlar (bkz. ZeusCapabilityVerifier javadoc — işaretçi sınıf dize olarak tutulur, zeus-base
 * zeus-ai'a bağımlı DEĞİLDİR). Testteki sahte ClassLoader, adı "izin listesinde" olan sınıflar
 * için gerçek yükleyiciye (bu sınıfın classloader'ına) devrediyor; dolayısıyla "işaretçi var"
 * durumunu doğru sınayabilmek için classpath'te GERÇEKTEN yüklenebilir aynı adlı bir sınıf
 * olması gerekir. Bu sınıf yalnızca test kaynağıdır (target/test-classes) — ana derlemeye
 * (target/classes) veya yayınlanan zeus-base jar'ına GİRMEZ, dolayısıyla zeus-base'e zeus-ai
 * üzerinde gerçek bir derleme zamanı bağımlılığı kazandırmaz.
 */
class ZeusAiAutoConfiguration {
}
