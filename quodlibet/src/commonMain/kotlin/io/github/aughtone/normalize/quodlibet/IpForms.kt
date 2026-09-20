package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.ComparableForm

/**
 * The comparable forms IP policies write.
 *
 * - [Ipv4Address]: a dotted-quad IPv4 address. Written by `ipv4.quad.dotted`, and by `ipv6.rfc5952` under
 *   `ipv4.mapped` or `ipv4.nat64` for an address those modes fold out, so an IPv4 address and its IPv4-mapped or
 *   NAT64 spelling are explicitly comparable. `ipv4.inet.aton` only offers it: its reading of `010` as 8
 *   is an interpretation, so comparing it with `ipv4.quad.dotted` is the caller's visible choice.
 * - [Ipv6Address]: an RFC 5952 IPv6 address, written by every `ipv6.rfc5952` policy.
 * - [Ipv4Network] and [Ipv6Network]: a network in the shared network form, written by strict CIDR input,
 *   masked CIDR input and block derivation alike, so a derived block and a range read from a list are
 *   explicitly comparable.
 */
object IpForms {
    /** A dotted-quad IPv4 address, written by every IPv4 policy and by the IPv6 modes that fold one out. */
    val Ipv4Address: ComparableForm = ComparableForm("ipv4.address")

    /** An RFC 5952 IPv6 address, written by every `ipv6.rfc5952` policy. */
    val Ipv6Address: ComparableForm = ComparableForm("ipv6.address")

    /** An IPv4 network as `address/prefix`, written by CIDR input and block derivation alike. */
    val Ipv4Network: ComparableForm = ComparableForm("ipv4.network")

    /** An IPv6 network as `address/prefix`, written by CIDR input and block derivation alike. */
    val Ipv6Network: ComparableForm = ComparableForm("ipv6.network")
}
