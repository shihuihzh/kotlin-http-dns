package xyz.zhanhao

import xyz.zhanhao.dns.DnsServer
import xyz.zhanhao.dns.TOKEN


fun main(args: Array<String>) {
    val reuqestUrl = "http://119.29.29.29/d?dn=$TOKEN&ip=1.1.1.1"
    DnsServer(5354, reuqestUrl, ";").start()
}