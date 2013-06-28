*otj* app- transfer tool with command-line syntax compatible to [opentxs](https://github.com/FellowTraveler/Open-Transactions/wiki/opentxs)
========

#### supported commands: ####

 * `transfer --hisacct ACCOUNT_ID --args "amount 100 memo \" \""` send 100 to ACCOUNT_ID
 * `acceptall` processInbox 
 * `balance` print account balance
 * `procnym` processNymbox
 * `reload` reload trans# from our server nymfile
 
#### supported options: ####
 * `--clean` refresh otj by deleting `./client` folder
 * `--new` create fresh asset account 
 * `--asset` specify asset type to use, can be `--asset silver` or `--asset d2Af13...`
 * `--dir` specify otj client directory, default is `./client`
 
You can see how to use it looking at [bash/](bash/) scripts
e.g. [transfer0.sh](bash/transfer0.sh) receives 100 from *opentxs*, sends him back *10* to check balances finally
