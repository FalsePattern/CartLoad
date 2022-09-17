/*
 * Copyright (C) 2022 FalsePattern
 * All Rights Reserved
 *
 * The above copyright notice, this permission notice and the word "SNEED"
 * shall be included in all copies or substantial portions of the Software.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.falsepattern.cartload;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.*;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import lombok.val;
import lombok.var;

import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.minecart.MinecartUpdateEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mod(modid = Tags.MODID,
     version = Tags.VERSION,
     name = Tags.MODNAME,
     acceptedMinecraftVersions = "[1.7.10]",
     acceptableRemoteVersions = "*")
public class CartLoad {
    public static final double SPEED_EPSILON = 0.0001;
    public static final int STATIONARY_TIMEOUT = 100;
    private static final Map<EntityMinecart, ForgeChunkManager.Ticket> cartTickets = new HashMap<>();
    private static final TObjectIntMap<EntityMinecart> timers = new TObjectIntHashMap<>();
    private static CartLoad instance;
    private int cleanupCounter = 0;

    public CartLoad() {
        instance = this;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        ForgeChunkManager.setForcedChunkLoadingCallback(instance, (tickets, world) -> tickets.forEach((ticket) -> {
            if (ticket.getType() == ForgeChunkManager.Type.ENTITY) {
                val entity = ticket.getEntity();
                if (entity instanceof EntityMinecart) {
                    val minecart = (EntityMinecart) entity;
                    cartTickets.put(minecart, ticket);
                    processMinecart(minecart);
                }
            }
        }));
    }

    @SubscribeEvent
    public void onTick(TickEvent e) {
        if (e.side == Side.SERVER && e.phase == TickEvent.Phase.END) {
            cleanupCounter++;
            if (cleanupCounter < 100) {
                return;
            }
            cleanupCounter = 0;
            List<EntityMinecart> dead = null;
            for (val cart: cartTickets.keySet()) {
                if (!cart.isDead) {
                    continue;
                }
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(cart);
            }
            if (dead == null) {
                return;
            }
            for (val cart: dead) {
                ForgeChunkManager.releaseTicket(cartTickets.remove(cart));
            }
        }
    }

    @SubscribeEvent
    public void onMinecart(MinecartUpdateEvent e) {
        processMinecart(e.minecart);
    }

    private static void processMinecart(EntityMinecart minecart) {
        var ticket = cartTickets.get(minecart);
        double speed = Math.abs(minecart.motionX) + Math.abs(minecart.motionY) + Math.abs(minecart.motionZ);
        if (speed < SPEED_EPSILON) {
            if (ticket != null) {
                int time = timers.get(minecart) + 1;
                if (time >= STATIONARY_TIMEOUT) {
                    ForgeChunkManager.releaseTicket(ticket);
                    cartTickets.remove(minecart);
                    timers.remove(minecart);
                } else {
                    timers.put(minecart, time);
                }
            }
            return;
        } else {
            timers.remove(minecart);
        }
        if (ticket == null) {
            ticket = ForgeChunkManager.requestTicket(instance, minecart.worldObj, ForgeChunkManager.Type.ENTITY);
            ticket.bindEntity(minecart);
            ticket.setChunkListDepth(3);
            cartTickets.put(minecart, ticket);
        }
        val CX = Math.floorDiv((int) minecart.posX, 16);
        val CZ = Math.floorDiv((int) minecart.posZ, 16);
        val req = ticket.getChunkList();
        loadFront(req, ticket, new ChunkCoordIntPair(CX, CZ));
        val cX = (minecart.posX % 16 + 16) % 16;
        val cZ = (minecart.posZ % 16 + 16) % 16;
        if (cX < 2) {
            loadFront(req, ticket, new ChunkCoordIntPair(CX - 1, CZ));
        } else if (cX > 13) {
            loadFront(req, ticket, new ChunkCoordIntPair(CX + 1, CZ));
        }
        if (cZ < 2) {
            loadFront(req, ticket, new ChunkCoordIntPair(CX, CZ - 1));
        } else if (cZ > 13) {
            loadFront(req, ticket, new ChunkCoordIntPair(CX, CZ + 1));
        }
    }
    private static void loadFront(Set<ChunkCoordIntPair> req, ForgeChunkManager.Ticket ticket, ChunkCoordIntPair pos) {
        if (ticket == null || pos == null) {
            return;
        }
        if (!req.contains(pos)) {
            ForgeChunkManager.forceChunk(ticket, pos);
        } else {
            ForgeChunkManager.reorderChunk(ticket, pos);
        }
    }
}